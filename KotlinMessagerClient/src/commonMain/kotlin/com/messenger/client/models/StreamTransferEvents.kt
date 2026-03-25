package com.messenger.client.models

sealed class StreamTransferEvent {
    data class Offer(val data: StreamTransferOfferDto) : StreamTransferEvent()
    data class Accepted(val data: StreamTransferAcceptedDto) : StreamTransferEvent()
    data class Rejected(val data: StreamTransferRejectedDto) : StreamTransferEvent()
    data class Chunk(val data: StreamTransferChunkDto) : StreamTransferEvent()
    data class Ack(val data: StreamTransferAckDto) : StreamTransferEvent()
    data class Nack(val data: StreamTransferNackDto) : StreamTransferEvent()
    data class Resume(val data: StreamTransferResumeRequestDto) : StreamTransferEvent()
    data class Complete(val data: StreamTransferCompletedDto) : StreamTransferEvent()
    data class Canceled(val data: StreamTransferCanceledDto) : StreamTransferEvent()
}
