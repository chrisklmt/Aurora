package gr.hua.aurora.ble.transport

class AndroidBleTransportSender(
    private val frameWriter: BleGattTransportFrameWriter?,
    private val onLocalSendTraceReady: ((BleTransportLocalSendTrace) -> Unit)? = null
) : BleTransportSender {
    override fun send(
        plan: OutgoingBleTransportSendPlan,
        listener: BleTransportSender.Listener
    ) {
        val writer = frameWriter ?: run {
            listener.onSendResult(BleTransportSendResult.NotAvailable)
            return
        }
        val frames = plan.framesInSendOrder()
        val metrics = plan.metrics()

        sendFrame(
            writer = writer,
            plan = plan,
            metrics = metrics,
            frames = frames,
            frameIndex = 0,
            messageId = plan.messageId,
            attemptedWriteCount = 0,
            acceptedWriteCount = 0,
            listener = listener
        )
    }

    private fun sendFrame(
        writer: BleGattTransportFrameWriter,
        plan: OutgoingBleTransportSendPlan,
        metrics: OutgoingBleTransportSendPlanMetrics,
        frames: List<BleGattTransportFrame>,
        frameIndex: Int,
        messageId: String,
        attemptedWriteCount: Int,
        acceptedWriteCount: Int,
        listener: BleTransportSender.Listener
    ) {
        val frame = frames[frameIndex]
        val nextAttemptedWriteCount = attemptedWriteCount + 1

        try {
            writer.write(
                frame = frame,
                listener = object : BleGattTransportFrameWriter.Listener {
                    override fun onWriteResult(result: BleGattTransportFrameWriteResult) {
                        when (result) {
                            BleGattTransportFrameWriteResult.Accepted -> {
                                val nextAcceptedWriteCount = acceptedWriteCount + 1
                                if (frameIndex == frames.lastIndex) {
                                    emitLocalSendTrace(
                                        plan = plan,
                                        metrics = metrics,
                                        chunksWriteAttempted = nextAttemptedWriteCount,
                                        chunksQueued = nextAcceptedWriteCount,
                                        lastLocalWriteResult = BleTransportSendResult.QueuedLocally
                                    )
                                    listener.onSendResult(BleTransportSendResult.QueuedLocally)
                                } else {
                                    sendFrame(
                                        writer = writer,
                                        plan = plan,
                                        metrics = metrics,
                                        frames = frames,
                                        frameIndex = frameIndex + 1,
                                        messageId = messageId,
                                        attemptedWriteCount = nextAttemptedWriteCount,
                                        acceptedWriteCount = nextAcceptedWriteCount,
                                        listener = listener
                                    )
                                }
                            }

                            BleGattTransportFrameWriteResult.NotAvailable -> {
                                val failedResult = if (frameIndex == 0) {
                                    BleTransportSendResult.NotAvailable
                                } else {
                                    BleTransportSendResult.Failed(
                                        reason = "Frame ${frameIndex + 1} of ${frames.size} for messageId=$messageId was not accepted."
                                    )
                                }
                                emitLocalSendTrace(
                                    plan = plan,
                                    metrics = metrics,
                                    chunksWriteAttempted = nextAttemptedWriteCount,
                                    chunksQueued = acceptedWriteCount,
                                    lastLocalWriteResult = failedResult
                                )
                                if (frameIndex == 0) {
                                    listener.onSendResult(BleTransportSendResult.NotAvailable)
                                } else {
                                    listener.onSendResult(
                                        failedResult
                                    )
                                }
                            }
                        }
                    }
                }
            )
        } catch (runtimeException: RuntimeException) {
            val failedResult = BleTransportSendResult.Failed(
                reason = "Frame ${frameIndex + 1} of ${frames.size} for messageId=$messageId failed before local acceptance: ${runtimeException::class.java.simpleName}."
            )
            emitLocalSendTrace(
                plan = plan,
                metrics = metrics,
                chunksWriteAttempted = nextAttemptedWriteCount,
                chunksQueued = acceptedWriteCount,
                lastLocalWriteResult = failedResult
            )
            listener.onSendResult(
                failedResult
            )
        }
    }

    private fun emitLocalSendTrace(
        plan: OutgoingBleTransportSendPlan,
        metrics: OutgoingBleTransportSendPlanMetrics,
        chunksWriteAttempted: Int,
        chunksQueued: Int,
        lastLocalWriteResult: BleTransportSendResult
    ) {
        onLocalSendTraceReady?.invoke(
            BleTransportLocalSendTrace(
                messageId = plan.messageId,
                targetPeerId = plan.targetPeerId,
                groupId = plan.groupId,
                encodedPayloadByteCount = metrics.encodedPayloadByteCount,
                chunkCount = metrics.chunkCount,
                chunkPayloadSizes = metrics.chunkPayloadSizes,
                frameEncodedSizes = metrics.frameEncodedSizes,
                chunksQueued = chunksQueued,
                chunksWriteAttempted = chunksWriteAttempted,
                lastLocalWriteResult = lastLocalWriteResult::class.simpleName
                    ?: lastLocalWriteResult.toString()
            )
        )
    }
}
