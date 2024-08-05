package xtdb.kafka.connect

import clojure.java.api.Clojure
import clojure.lang.IFn
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.connect.sink.SinkRecord
import org.apache.kafka.connect.sink.SinkTask
import xtdb.api.IXtdb
import xtdb.api.XtdbClient
import java.net.URI

class XtdbSinkTask : SinkTask() {
    companion object {
        private val submitSinkRecords: IFn

        init {
            Clojure.`var`("clojure.core/require").invoke(Clojure.read("xtdb.kafka.connect"))
            submitSinkRecords = Clojure.`var`("xtdb.kafka.connect/submit-sink-records")
        }
    }

    private var config: XtdbSinkConfig? = null
    private var client: IXtdb? = null

    override fun version(): String = XtdbSinkConnector().version()

    override fun start(props: Map<String, String>) {
        val config = XtdbSinkConfig.parse(props).also { this.config = it }
        this.client = XtdbClient.openClient(URI(config.url).toURL())
    }

    override fun put(sinkRecords: Collection<SinkRecord>) {
        submitSinkRecords(client, config, sinkRecords)
    }

    override fun flush(offsets: Map<TopicPartition, OffsetAndMetadata>) {
    }

    override fun stop() {
        try {
            client?.close()
            client = null
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
