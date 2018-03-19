package io.scalecube.streams;

import io.scalecube.transport.Address;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;
import rx.subscriptions.CompositeSubscription;

public final class ServerStreamProcessorFactory {

  private final ListeningServerStream remoteEventStream;
  private final DefaultEventStream localEventStream = new DefaultEventStream();

  private final Subject<StreamProcessor, StreamProcessor> streamProcessorSubject =
      PublishSubject.<StreamProcessor>create().toSerialized();

  private final CompositeSubscription subscriptions = new CompositeSubscription();

  /**
   * Constructor for this factory. Right away defines logic for bidirectional communication with respect to server side
   * semantics.
   * 
   * @param remoteEventStream given {@link ServerStream} object created and operated somewhere.
   */
  private ServerStreamProcessorFactory(ListeningServerStream remoteEventStream) {
    this.remoteEventStream = remoteEventStream;

    // request logic: remote stream => local stream
    subscriptions.add(
        remoteEventStream.listenReadSuccess()
            .subscribe(event -> {
              Address address = event.getAddress();
              StreamMessage message = event.getMessageOrThrow();
              String id = message.getSenderId();

              // Hint: at this point some sort of sanity check is needed to see is there somebody who's listening on new
              // stream because next code is about to create entry in map so it's kind of waste of resource
              // if nobody don't listen for new stream processor
              ChannelContext channelContext =
                  ChannelContext.createIfAbsent(id, address, this::initChannelContext);

              // forward read
              channelContext.postReadSuccess(message);
            }));

    // connection logic: connection lost => local stream
    subscriptions.add(
        remoteEventStream.listenChannelContextClosed()
            .subscribe(event -> localEventStream.onNext(event.getAddress(), event)));
  }

  private void initChannelContext(ChannelContext channelContext) {
    // response logic: local write => remote stream
    channelContext.listenWrite()
        .map(event -> {
          String id = channelContext.getId();
          StreamMessage message = event.getMessageOrThrow();
          // reset outgoing message identity with channelContext's identity
          return StreamMessage.copyFrom(message).senderId(id).build();
        })
        .subscribe(remoteEventStream::send);

    // bind channel context
    localEventStream.subscribe(channelContext);

    // emit stream processor arrived
    streamProcessorSubject.onNext(new DefaultStreamProcessor(channelContext, localEventStream));
  }

  /**
   * Creates stream processor factory.
   * 
   * @param remoteEventStream server stream created somewhere; this stream is a source for incoming events upon which
   *        factory will apply its processing logic and server side semantics.
   * @return stream processor factory
   * @see #ServerStreamProcessorFactory(ListeningServerStream)
   */
  public static ServerStreamProcessorFactory newServerStreamProcessorFactory(ListeningServerStream remoteEventStream) {
    return new ServerStreamProcessorFactory(remoteEventStream);
  }

  /**
   * Returns subscrption point where to listen for newly created {@link StreamProcessor} objects.
   * 
   * @return observalbe to listen for incoming server stream processors
   */
  public Observable<StreamProcessor> listenServerStreamProcessor() {
    return streamProcessorSubject.asObservable().onBackpressureBuffer();
  }

  /**
   * Clears subscriptions and closes local {@link EventStream} (which inherently unsubscribes all subscribed channel
   * contexts on it).
   */
  public void close() {
    subscriptions.clear();
    localEventStream.close();
  }
}
