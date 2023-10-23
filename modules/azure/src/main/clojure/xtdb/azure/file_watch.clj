(ns xtdb.azure.file-watch
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [xtdb.file-list :as file-list])
  (:import [com.azure.core.credential TokenCredential]
           [com.azure.messaging.servicebus ServiceBusClientBuilder ServiceBusReceivedMessageContext ServiceBusErrorContext ServiceBusProcessorClient]
           [com.azure.messaging.servicebus.administration ServiceBusAdministrationClient ServiceBusAdministrationClientBuilder]
           [com.azure.storage.blob.models ListBlobsOptions BlobItem]
           [com.azure.storage.blob BlobContainerClient]
           [java.lang AutoCloseable]
           [java.util NavigableSet UUID]
           [java.util.function Consumer]))

(defn file-list-init [{:keys [^BlobContainerClient blob-container-client prefix]}  ^NavigableSet file-name-cache]
  (let [list-blob-opts (cond-> (ListBlobsOptions.)
                         prefix (.setPrefix prefix))
        filename-list (->> (.listBlobs blob-container-client list-blob-opts nil)
                           (.iterator)
                           (iterator-seq)
                           (mapv (fn [^BlobItem blob-item]
                                   (subs (.getName blob-item) (count prefix)))))]
    (file-list/add-filename-list file-name-cache filename-list)))

(defn mk-short-uuid []
  (subs (str (UUID/randomUUID)) 0 8))

(defn setup-topic-subscription [{:keys [^TokenCredential azure-credential servicebus-namespace servicebus-topic-name]}]
  (let [servicebus-admin-client (-> (ServiceBusAdministrationClientBuilder.)
                                    (.credential (format "%s.servicebus.windows.net" servicebus-namespace)
                                                 azure-credential)
                                    (.buildClient))

        subscription-name (format "xtdb-topic-subscription-%s" (mk-short-uuid))]

    (log/info "Creating new subscription on topic %s, subscription name " servicebus-topic-name subscription-name)
    (-> servicebus-admin-client
        (.createSubscription servicebus-topic-name subscription-name))

    {:servicebus-admin-client servicebus-admin-client
     :servicebus-topic-name servicebus-topic-name
     :subscription-name subscription-name}))

(defn open-file-list-watcher [{:keys [^BlobContainerClient blob-container-client ^TokenCredential azure-credential servicebus-namespace container prefix] :as opts} ^NavigableSet file-name-cache]
  (let [;; Create queue that will subscribe to sns topic for notifications
        {:keys [^ServiceBusAdministrationClient servicebus-admin-client servicebus-topic-name subscription-name]} (setup-topic-subscription opts)

        _ (log/info "Initializing filename list from container " container)
         ;; Init the filename cache with current files
        _ (file-list-init opts file-name-cache)

        url-suffix (if prefix (str "/" prefix) "/")
        base-file-url (str (.getBlobContainerUrl blob-container-client) url-suffix)
        ^ServiceBusProcessorClient processor-client (-> (ServiceBusClientBuilder.)
                                                        (.fullyQualifiedNamespace (format "%s.servicebus.windows.net" servicebus-namespace))
                                                        (.credential azure-credential)
                                                        (.processor)
                                                        (.topicName servicebus-topic-name)
                                                        (.subscriptionName subscription-name)
                                                        (.processMessage (reify Consumer
                                                                           (accept [_ msg]
                                                                             (let [parsed-msg (json/read-str (.. ^ServiceBusReceivedMessageContext msg getMessage getBody toString) :key-fn keyword)
                                                                                   msg-data (:data parsed-msg)
                                                                                   event-type (get {"PutBlob" :create "DeleteBlob" :delete} (:api msg-data))
                                                                                   file-url (:url msg-data)
                                                                                   file (when (string/starts-with? file-url base-file-url)
                                                                                          (subs file-url (count base-file-url)))]
                                                                               (log/debug (format "Message received, performing %s on file %s" event-type file))
                                                                               (when (and event-type file)
                                                                                 (cond
                                                                                   (= event-type :create) (file-list/add-filename file-name-cache file)
                                                                                   (= event-type :delete) (file-list/remove-filename file-name-cache file)))))))
                                                        (.processError (reify Consumer
                                                                         (accept [_ msg]
                                                                           (log/error "Error when processing message from service bus queue - " (.getException ^ServiceBusErrorContext msg)))))
                                                        (.buildProcessorClient))]

      ;; Start processing messages from the queue
    (log/info "Watching for filechanges from container " container)
    (.start processor-client)

    ;; Return an auto closeable object that clears up the processor and subscription
    (reify 
      AutoCloseable
      (close [_]
        (log/info "Stopping & closing filechange processor client")
        (.close processor-client)

        (log/info (format "Removing subscription %s on topic %s " servicebus-topic-name subscription-name))
        (.deleteSubscription servicebus-admin-client servicebus-topic-name subscription-name)))))
