package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Test

class HybridBootstrapCommandExecutorConfigTest {
    @Test
    fun defaultConfigModeIsNoOp() {
        val config = HybridBootstrapCommandExecutorConfig()

        assertEquals(HybridBootstrapCommandExecutorMode.NO_OP, config.mode)
    }

    @Test
    fun defaultConfigRejectionReasonIsExactlyHybridBootstrapExecutionIsDisabled() {
        val config = HybridBootstrapCommandExecutorConfig()

        assertEquals(
            "Hybrid bootstrap execution is disabled.",
            config.noOpRejectionReason
        )
    }
}
