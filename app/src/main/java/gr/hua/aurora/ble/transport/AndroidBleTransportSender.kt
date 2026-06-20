package gr.hua.aurora.ble.transport

class AndroidBleTransportSender(
    private val frameWriter: BleGattTransportFrameWriter?
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

        sendFrame(
            writer = writer,
            frames = frames,
            frameIndex = 0,
            messageId = plan.messageId,
            listener = listener
        )
    }

    private fun sendFrame(
        writer: BleGattTransportFrameWriter,
        frames: List<BleGattTransportFrame>,
        frameIndex: Int,
        messageId: String,
        listener: BleTransportSender.Listener
    ) {
        val frame = frames[frameIndex]

        try {
            writer.write(
                frame = frame,
                listener = object : BleGattTransportFrameWriter.Listener {
                    override fun onWriteResult(result: BleGattTransportFrameWriteResult) {
                        when (result) {
                            BleGattTransportFrameWriteResult.Accepted -> {
                                if (frameIndex == frames.lastIndex) {
                                    listener.onSendResult(BleTransportSendResult.QueuedLocally)
                                } else {
                                    sendFrame(
                                        writer = writer,
                                        frames = frames,
                                        frameIndex = frameIndex + 1,
                                        messageId = messageId,
                                        listener = listener
                                    )
                                }
                            }

                            BleGattTransportFrameWriteResult.NotAvailable -> {
                                if (frameIndex == 0) {
                                    listener.onSendResult(BleTransportSendResult.NotAvailable)
                                } else {
                                    listener.onSendResult(
                                        BleTransportSendResult.Failed(
                                            reason = "Frame ${frameIndex + 1} of ${frames.size} for messageId=$messageId was not accepted."
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            )
        } catch (runtimeException: RuntimeException) {
            listener.onSendResult(
                BleTransportSendResult.Failed(
                    reason = "Frame ${frameIndex + 1} of ${frames.size} for messageId=$messageId failed before local acceptance: ${runtimeException::class.java.simpleName}."
                )
            )
        }
    }
}
