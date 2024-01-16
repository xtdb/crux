(ns xtdb.azure
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.azure.file-watch :as azure-file-watch]
            [xtdb.azure.log :as tx-log]
            [xtdb.azure.object-store :as os]
            [xtdb.buffer-pool :as bp]
            [xtdb.log :as xtdb-log]
            [xtdb.time :as time]
            [xtdb.util :as util])
  (:import [com.azure.core.credential TokenCredential]
           [com.azure.core.management AzureEnvironment]
           [com.azure.core.management.profile AzureProfile]
           [com.azure.identity DefaultAzureCredentialBuilder]
           [com.azure.messaging.eventhubs EventHubClientBuilder]
           [com.azure.resourcemanager.eventhubs EventHubsManager]
           [com.azure.resourcemanager.eventhubs.models EventHub EventHub$Definition EventHubs]
           [com.azure.storage.blob BlobServiceClientBuilder] 
           [java.util.concurrent ConcurrentSkipListSet]
           [xtdb.api.storage ObjectStore]
           [xtdb.api AzureObjectStoreFactory]))

(defmethod bp/->object-store-factory ::object-store [_ {:keys [storage-account container servicebus-namespace servicebus-topic-name prefix]}]
  (cond-> (AzureObjectStoreFactory. storage-account container servicebus-namespace servicebus-topic-name) 
    prefix (.prefix (util/->path prefix))))

;; No minimum block size in azure
(def minimum-part-size 0)

(defn open-object-store ^ObjectStore [^AzureObjectStoreFactory factory]
  (let [credential (.build (DefaultAzureCredentialBuilder.))
        storage-account (.getStorageAccount factory)
        container (.getContainer factory)
        servicebus-namespace (.getServicebusNamespace factory)
        servicebus-topic-name (.getServicebusTopicName factory)
        prefix (.getPrefix factory)
        blob-service-client (cond-> (-> (BlobServiceClientBuilder.)
                                        (.endpoint (str "https://" storage-account ".blob.core.windows.net"))
                                        (.credential credential)
                                        (.buildClient)))
        blob-client (.getBlobContainerClient blob-service-client container)
        file-name-cache (ConcurrentSkipListSet.)
        ;; Watch azure container for changes
        file-list-watcher (azure-file-watch/open-file-list-watcher {:storage-account storage-account
                                                                    :container container
                                                                    :servicebus-namespace servicebus-namespace
                                                                    :servicebus-topic-name servicebus-topic-name
                                                                    :prefix prefix
                                                                    :blob-container-client blob-client
                                                                    :azure-credential credential}
                                                                   file-name-cache)]
    (os/->AzureBlobObjectStore blob-client
                               prefix
                               minimum-part-size
                               file-name-cache
                               file-list-watcher)))

(s/def ::resource-group-name string?)
(s/def ::namespace string?)
(s/def ::event-hub-name string?)
(s/def ::create-event-hub? boolean?)
(s/def ::retention-period-in-days number?)
(s/def ::max-wait-time ::time/duration)
(s/def ::poll-sleep-duration ::time/duration)

(derive ::event-hub-log :xtdb/log)

(defmethod ig/prep-key ::event-hub-log [_ opts]
  (-> (merge {:create-event-hub? false
              :max-wait-time "PT1S"
              :poll-sleep-duration "PT1S"
              :retention-period-in-days 7} opts)
      (util/maybe-update :max-wait-time time/->duration)))

(defmethod ig/pre-init-spec ::event-hub-log [_]
  (s/keys :req-un [::namespace ::event-hub-name ::max-wait-time ::create-event-hub? ::retention-period-in-days ::poll-sleep-duration]
          :opt-un [::resource-group-name]))

(defn resource-group-present? [{:keys [resource-group-name]}]
  (when-not resource-group-name
    (throw (IllegalArgumentException. "Must provide :resource-group-name when creating an eventhub automatically."))))

(defn create-event-hub-if-not-exists [^TokenCredential azure-credential {:keys [resource-group-name namespace event-hub-name retention-period-in-days]}]
  (let [event-hub-manager (EventHubsManager/authenticate azure-credential (AzureProfile. (AzureEnvironment/AZURE)))
        ^EventHubs event-hubs (.eventHubs event-hub-manager)
        event-hub-exists? (some
                           #(= event-hub-name (.name ^EventHub %))
                           (.listByNamespace event-hubs resource-group-name namespace))]
    (try
      (when-not event-hub-exists?
        (-> event-hubs
            ^EventHub$Definition (.define event-hub-name)
            (.withExistingNamespace resource-group-name namespace)
            (.withPartitionCount 1)
            (.withRetentionPeriodInDays retention-period-in-days)
            (.create)))
      (catch Exception e
        (log/error "Error when creating event hub - " (.getMessage e))
        (throw e)))))

(defmethod ig/init-key ::event-hub-log [_ {:keys [create-event-hub? namespace event-hub-name max-wait-time poll-sleep-duration] :as opts}]
  (let [credential (.build (DefaultAzureCredentialBuilder.))
        fully-qualified-namespace (format "%s.servicebus.windows.net" namespace)
        event-hub-client-builder (-> (EventHubClientBuilder.)
                                     (.consumerGroup "$DEFAULT")
                                     (.credential credential)
                                     (.fullyQualifiedNamespace fully-qualified-namespace)
                                     (.eventHubName event-hub-name))
        subscriber-handler (xtdb-log/->notifying-subscriber-handler nil)]

    (when create-event-hub?
      (resource-group-present? opts)
      (create-event-hub-if-not-exists credential opts))

    (tx-log/->EventHubLog subscriber-handler
                          (.buildAsyncProducerClient event-hub-client-builder)
                          (.buildConsumerClient event-hub-client-builder)
                          max-wait-time
                          poll-sleep-duration)))

(defmethod ig/halt-key! ::event-hub-log [_ log]
  (util/try-close log))
