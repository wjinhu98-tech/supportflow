package dev.supportflow.chat.infrastructure.deepseek;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Enforces the byte bound while receiving, before materializing the complete response. */
final class DeepSeekResponseBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private long remaining;
    private Flow.Subscription subscription;
    private boolean failed;

    DeepSeekResponseBodySubscriber(int maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("Response bound must be positive");
        remaining = maximumBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (failed) return;
        long received = 0;
        for (ByteBuffer buffer : buffers) received += buffer.remaining();
        if (received > remaining) {
            failed = true;
            subscription.cancel();
            delegate.onError(new ResponseLimitExceededException());
            return;
        }
        remaining -= received;
        delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable throwable) {
        if (!failed) {
            failed = true;
            delegate.onError(throwable);
        }
    }

    @Override
    public void onComplete() {
        if (!failed) delegate.onComplete();
    }

    static final class ResponseLimitExceededException extends IOException {
        ResponseLimitExceededException() {
            super("Model response exceeded the configured byte bound");
        }
    }
}
