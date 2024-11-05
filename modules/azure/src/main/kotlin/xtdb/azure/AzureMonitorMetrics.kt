package xtdb.azure

import io.micrometer.azuremonitor.AzureMonitorConfig
import io.micrometer.azuremonitor.AzureMonitorMeterRegistry
import io.micrometer.core.instrument.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xtdb.api.module.XtdbModule
import xtdb.api.StringWithEnvVarSerde
import xtdb.api.Xtdb

@Serializable
@SerialName("!AzureMonitor")
class AzureMonitorMetrics(
    @Serializable(StringWithEnvVarSerde::class) val instrumentationKey: String = "xtdb.metrics",
) : XtdbModule.Factory {

    override val moduleKey = "xtdb.metrics.azure-monitor"

    override fun openModule(xtdb: Xtdb): XtdbModule {
        val reg = AzureMonitorMeterRegistry(
            object : AzureMonitorConfig {
                override fun get(key: String) = null
                override fun instrumentationKey() = instrumentationKey
            },
            Clock.SYSTEM
        )

        xtdb.addMeterRegistry(reg)

        return object : XtdbModule {
            override fun close() {}
        }
    }

    /**
     * @suppress
     */
    class Registration : XtdbModule.Registration {
        override fun register(registry: XtdbModule.Registry) {
            registry.registerModuleFactory(AzureMonitorMetrics::class)
        }
    }
}