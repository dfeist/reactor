/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package reactor.rx;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Dispatcher;
import reactor.core.Environment;
import reactor.core.dispatch.SynchronousDispatcher;
import reactor.event.Observable;
import reactor.event.selector.ClassSelector;
import reactor.event.selector.Selectors;
import reactor.function.*;
import reactor.function.support.Tap;
import reactor.queue.CompletableBlockingQueue;
import reactor.queue.CompletableLinkedQueue;
import reactor.queue.CompletableQueue;
import reactor.rx.action.*;
import reactor.rx.action.support.NonBlocking;
import reactor.rx.action.support.TapAndControls;
import reactor.rx.stream.Broadcaster;
import reactor.rx.stream.GroupedStream;
import reactor.rx.stream.LiftStream;
import reactor.rx.subscription.PushSubscription;
import reactor.timer.Timer;
import reactor.tuple.Tuple2;
import reactor.tuple.TupleN;
import reactor.util.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Base class for components designed to provide a succinct API for working with future values.
 * Provides base functionality and an internal contract for subclasses that make use of
 * the {@link #map(reactor.function.Function)} and {@link #filter(reactor.function.Predicate)} methods.
 * <p>
 * A Stream can be implemented to perform specific actions on callbacks (doNext,doComplete,doError,doSubscribe).
 * It is an asynchronous boundary and will run the callbacks using the input {@link Dispatcher}. Stream can
 * eventually produce a result {@param <O>} and will offer cascading over its own subscribers.
 * <p>
 * *
 * Typically, new {@code Stream} aren't created directly. To create a {@code Stream},
 * create a {@link Streams} and configure it with the appropriate {@link Environment},
 * {@link Dispatcher}, and other settings.
 *
 * @param <O> The type of the output values
 * @author Stephane Maldini
 * @author Jon Brisbin
 * @since 1.1, 2.0
 */
public abstract class Stream<O> implements Publisher<O>, NonBlocking {


	protected Stream() {
	}

	/**
	 * Cast the current Stream flowing data type into a target class type.
	 *
	 * @param <E> the {@link Action} output type
	 * @return the current {link Stream} instance casted
	 * @since 2.0
	 */
	@SuppressWarnings({"unchecked", "unused"})
	public final <E> Stream<E> cast(@Nonnull final Class<E> stream) {
		return (Stream<E>) this;
	}

	/**
	 * Defer the subscription of an {@link Action} to the actual pipeline.
	 * Terminal operations such as {@link this#consume(Consumer)} will start the subscription chain.
	 * It will listen for current Stream signals and will be eventually producing signals as well (subscribe,error,
	 * complete,next).
	 * <p>
	 * The action is returned for functional-style chaining.
	 *
	 * @param <V>    the {@link reactor.rx.action.Action} output type
	 * @param action the function to map a provided dispatcher to a fresh Action to subscribe.
	 * @return the passed action
	 * @see {@link org.reactivestreams.Publisher#subscribe(org.reactivestreams.Subscriber)}
	 * @since 2.0
	 */
	public <V> Stream<V> lift(@Nonnull final Function<? super Dispatcher, ? extends Action<? super O, ? extends V>>
			                          action) {
		return new LiftStream<>(this, action);
	}

	/**
	 * Subscribe an {@link Subscriber} to the actual pipeline to consume current Stream signals (error,complete,next,
	 * subscribe).
	 * Return the actual Subscriber that can be an implementation of {@link reactor.core.processor.Processor} and chain
	 * more
	 * work behind.
	 *
	 * @param subscriber the processor to subscribe.
	 * @param <E>        the {@link Subscriber} output type
	 * @return the passed subscriber
	 * @see {@link org.reactivestreams.Publisher#subscribe(org.reactivestreams.Subscriber)}
	 * @since 2.0
	 */
	public final <E extends Subscriber<? super O>> E chain(@Nonnull final E subscriber) {
		this.subscribe(subscriber);
		return subscriber;
	}

	/**
	 * Assign an error handler to exceptions of the given type. Will not stop error propagation, use when(class,
	 * publisher), retry, ignoreError or recover to actively deal with the exception
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError       the error handler for each exception
	 * @param <E>           type of the exception to handle
	 * @return {@literal new Stream}
	 */
	@SuppressWarnings("unchecked")
	public final <E extends Throwable> Stream<O> when(@Nonnull final Class<E> exceptionType,
	                                                  @Nonnull final Consumer<E> onError) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			ClassSelector classSelector = Selectors.T(exceptionType);

			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new ErrorAction<O, E>(getDispatcher(), classSelector, onError, null);
			}
		});
	}

	/**
	 * Subscribe to a fallback publisher when any exception occurs.
	 *
	 * @param fallback the error handler for each exception
	 * @return {@literal new Stream}
	 */
	public final Stream<O> onErrorResumeNext(@Nonnull final Publisher<? extends O> fallback) {
		return onErrorResumeNext(Throwable.class, fallback);
	}

	/**
	 * Subscribe to a fallback publisher when exceptions of the given type occur, otherwise propagate the error.
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param fallback      the error handler for each exception
	 * @param <E>           type of the exception to handle
	 * @return {@literal new Stream}
	 */
	@SuppressWarnings("unchecked")
	public final <E extends Throwable> Stream<O> onErrorResumeNext(@Nonnull final Class<E> exceptionType,
	                                                               @Nonnull final Publisher<? extends O> fallback) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			ClassSelector classSelector = Selectors.T(exceptionType);

			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new ErrorAction<O, E>(getDispatcher(), classSelector, null, fallback);
			}
		});
	}

	/**
	 * Produce a default value if any exception occurs.
	 *
	 * @param fallback the error handler for each exception
	 * @return {@literal new Stream}
	 */
	public final Stream<O> onErrorReturn(@Nonnull final Function<Throwable, ? extends O> fallback) {
		return onErrorReturn(Throwable.class, fallback);
	}

	/**
	 * Produce a default value when exceptions of the given type occur, otherwise propagate the error.
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param fallback      the error handler for each exception
	 * @param <E>           type of the exception to handle
	 * @return {@literal new Stream}
	 */
	@SuppressWarnings("unchecked")
	public final <E extends Throwable> Stream<O> onErrorReturn(@Nonnull final Class<E> exceptionType,
	                                                           @Nonnull final Function<E, ? extends O> fallback) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			ClassSelector classSelector = Selectors.T(exceptionType);

			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new ErrorReturnAction<O, E>(getDispatcher(), classSelector, fallback);
			}
		});
	}

	/**
	 * Materialize an error state into a downstream event.
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param <E>           type of the exception to handle
	 * @return {@literal new Stream}
	 * @since 2.0
	 */
	public final <E extends Throwable> Stream<E> recover(@Nonnull final Class<E> exceptionType) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends E>>() {
			ClassSelector classSelector = Selectors.T(exceptionType);

			@Override
			public Action<? super O, ? extends E> apply(Dispatcher dispatcher) {
				return new RecoverAction<O, E>(getDispatcher(), classSelector);
			}
		});
	}

	/**
	 * Transform the incoming onSubscribe, onNext, onError and onComplete signals into {@link reactor.rx.Signal}.
	 * Since the error is materialized as a {@code Signal}, the propagation will be stopped.
	 * Complete signal will first emit a {@code Signal.complete()} and then effectively complete the stream.
	 *
	 * @return {@literal new Stream}
	 */
	public final Stream<Signal<O>> materialize() {
		return lift(new Function<Dispatcher, Action<? super O, ? extends Signal<O>>>() {
			@Override
			public Action<? super O, ? extends Signal<O>> apply(Dispatcher dispatcher) {
				return new MaterializeAction<O>(getDispatcher());
			}
		});
	}

	/**
	 * Pass values accepted by this {@code Stream} into the given {@link Observable}, notifying with the given key.
	 *
	 * @param key        the key to notify on
	 * @param observable the {@link Observable} to notify
	 * @return {@literal new Stream}
	 * @since 1.1, 2.0
	 */
	public final Controls notify(@Nonnull final Object key, @Nonnull final Observable observable) {
		ObservableAction<O> observableAction = new ObservableAction<O>(getDispatcher(), observable, key);
		subscribe(observableAction);
		return observableAction;
	}


	/**
	 * Subscribe a new {@link Broadcaster} and return it for future subscribers interactions. Effectively it turns any
	 * stream into an Hot Stream where subscribers will only values from the time T when they subscribe to the returned
	 * stream. Complete and Error signals are however retained unless {@link this#keepAlive()} has been called before.
	 * <p>
	 *
	 * @return a new {@literal stream} whose values are broadcasted to all subscribers
	 */
	public final Stream<O> broadcast() {
		return broadcastOn(getDispatcher());
	}

	/**
	 * Subscribe a new {@link Broadcaster} and return it for future subscribers interactions. Effectively it turns any
	 * stream into an Hot Stream where subscribers will only values from the time T when they subscribe to the returned
	 * stream. Complete and Error signals are however retained unless {@link this#keepAlive()} has been called before.
	 * <p>
	 *
	 * @param dispatcher         the dispatcher to run the signals
	 * @return a new {@literal stream} whose values are broadcasted to all subscribers
	 */
	public final Stream<O> broadcastOn(Dispatcher dispatcher) {
		Broadcaster<O> broadcaster = new Broadcaster<O>(dispatcher, getCapacity()).env(getEnvironment());
		return broadcastTo(broadcaster);
	}


	/**
	 * Subscribe the passed subscriber, only creating once necessary upstream Subscriptions and returning itself.
	 * Mostly used by other broadcast actions which transform any Stream into a publish-subscribe Stream (every subscribers
	 * see all values).
	 * <p>
	 *
	 * @param subscriber         the subscriber to subscribe to this stream and return
	 * @param <E>    the hydrated generic type for the passed argument, allowing for method chaining
	 * @return {@param subscriber}
	 */
	public final <E extends Subscriber<? super O>> E  broadcastTo(E subscriber) {
		subscribe(subscriber);
		return subscriber;
	}

	/**
	 * Create a {@link reactor.function.support.Tap} that maintains a reference to the last value seen by this {@code
	 * Stream}. The {@link reactor.function.support.Tap} is
	 * continually updated when new values pass through the {@code Stream}.
	 *
	 * @return the new {@link reactor.function.support.Tap}
	 * @see Consumer
	 */
	public final TapAndControls<O> tap() {
		final Tap<O> tap = new Tap<>();
		return new TapAndControls<>(tap, consume(tap));
	}

	/**
	 * Defer a Controls operations ready to be requested.
	 *
	 * @return the consuming action
	 */
	public Controls consumeLater() {
		return consume(null);
	}

	/**
	 * Instruct the stream to request the produced subscription indefinitely. If the dispatcher
	 * is asynchronous (RingBufferDispatcher for instance), it will proceed the request asynchronously as well.
	 *
	 * @return the consuming action
	 */
	public Controls consume() {
		Controls controls = consume(null);
		controls.requestMore(Long.MAX_VALUE);
		return controls;
	}

	/**
	 * Instruct the action to request upstream subscription if any for N elements.
	 *
	 * @return a new {@link Controls} interface to operate on the materialized upstream
	 */
	public Controls consume(final long n) {
		Controls controls = consume(null);
		if(n > 0){
			controls.requestMore(n);
		}
		return controls;
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will consume any values accepted by this {@code
	 * Stream}. As such this a terminal action to be placed on a stream flow. Only error and complete signal will be
	 * signaled downstream. It will also eagerly prefetch upstream publisher.
	 * <p>
	 * For a passive version that observe and forward incoming data see {@link this#observe(reactor.function.Consumer)}
	 *
	 * @param consumer the consumer to invoke on each value
	 * @return a new {@link Controls} interface to operate on the materialized upstream
	 */
	public final Controls consume(final Consumer<? super O> consumer) {
		return consumeOn(consumer, getDispatcher());
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will consume any values accepted by this {@code
	 * Stream}. As such this a terminal action to be placed on a stream flow. Only error and complete signal will be
	 * signaled downstream. It will also eagerly prefetch upstream publisher.
	 * <p>
	 * For a passive version that observe and forward incoming data see {@link this#observe(reactor.function.Consumer)}
	 *
	 * @param consumer   the consumer to invoke on each value
	 * @param dispatcher the dispatcher to run the consumer
	 * @return a new {@link Controls} interface to operate on the materialized upstream
	 */
	public final Controls consumeOn(final Consumer<? super O> consumer, Dispatcher dispatcher) {
		TerminalCallbackAction<O> terminalCallbackAction = new TerminalCallbackAction<O>(dispatcher, consumer, null, null);
		subscribe(terminalCallbackAction);
		if(consumer != null){
			terminalCallbackAction.requestMore(terminalCallbackAction.getCapacity());
		}
		return terminalCallbackAction;
	}

	/**
	 * Attach 2 {@link Consumer} to this {@code Stream} that will consume any values signaled by this {@code
	 * Stream}. As such this a terminal action to be placed on a stream flow.
	 * Any Error signal will be consumed by the error consumer.
	 * Only error and complete signal will be
	 * signaled downstream. It will also eagerly prefetch upstream publisher.
	 * <p>
	 *
	 * @param consumer      the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on each error signal
	 * @return a new {@link Controls} interface to operate on the materialized upstream
	 */
	public final Controls consume(final Consumer<? super O> consumer,
	                                  Consumer<? super Throwable> errorConsumer) {
		return consumeOn(consumer, errorConsumer, getDispatcher());
	}

	/**
	 * Attach 2 {@link Consumer} to this {@code Stream} that will consume any values signaled by this {@code
	 * Stream}. As such this a terminal action to be placed on a stream flow.
	 * Any Error signal will be consumed by the error consumer.
	 * Only error and complete signal will be
	 * signaled downstream. It will also eagerly prefetch upstream publisher.
	 * <p>
	 *
	 * @param consumer      the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on each error signal
	 * @param dispatcher    the dispatcher to run the consumer
	 * @return a new {@link Controls} interface to operate on the materialized upstream
	 */
	public final Controls consumeOn(final Consumer<? super O> consumer,
	                                    Consumer<? super Throwable> errorConsumer, Dispatcher dispatcher) {
		return consumeOn(consumer, errorConsumer, null, dispatcher);
	}

	/**
	 * Attach 3 {@link Consumer} to this {@code Stream} that will consume any values signaled by this {@code
	 * Stream}. As such this a terminal action to be placed on a stream flow.
	 * Any Error signal will be consumed by the error consumer.
	 * The Complete signal will be consumed by the complete consumer.
	 * Only error and complete signal will be signaled downstream. It will also eagerly prefetch upstream publisher.
	 * <p>
	 *
	 * @param consumer         the consumer to invoke on each value
	 * @param errorConsumer    the consumer to invoke on each error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 * @return {@literal new Stream}
	 */
	public final Controls consume(final Consumer<? super O> consumer,
	                                  Consumer<? super Throwable> errorConsumer,
	                                  Consumer<Void> completeConsumer) {
		return consumeOn(consumer, errorConsumer, completeConsumer, getDispatcher());
	}


	/**
	 * Attach 3 {@link Consumer} to this {@code Stream} that will consume any values signaled by this {@code
	 * Stream}. As such this a terminal action to be placed on a stream flow.
	 * Any Error signal will be consumed by the error consumer.
	 * The Complete signal will be consumed by the complete consumer.
	 * Only error and complete signal will be signaled downstream. It will also eagerly prefetch upstream publisher.
	 * <p>
	 *
	 * @param consumer         the consumer to invoke on each value
	 * @param errorConsumer    the consumer to invoke on each error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 * @param dispatcher       the dispatcher to run the consumer
	 * @return {@literal new Stream}
	 */
	public final Controls consumeOn(final Consumer<? super O> consumer,
	                                    Consumer<? super Throwable> errorConsumer,
	                                    Consumer<Void> completeConsumer, Dispatcher dispatcher) {
		TerminalCallbackAction<O> terminalCallbackAction = new TerminalCallbackAction<O>(dispatcher, consumer,
				errorConsumer, completeConsumer);
		subscribe(terminalCallbackAction);
		terminalCallbackAction.requestMore(terminalCallbackAction.getCapacity());
		return terminalCallbackAction;
	}

	/**
	 * Assign a new Environment and its default Dispatcher to the returned Stream. If the dispatcher is different,
	 * the new action will take
	 * care of buffering incoming data into a StreamSubscription. Otherwise default behavior is picked:
	 * FireHose synchronous subscription is the parent stream != null
	 *
	 * @param environment the environment to get dispatcher from {@link reactor.core.Environment#getDefaultDispatcher()}
	 * @return a new {@link Stream} running on a different {@link Dispatcher}
	 */
	public final Stream<O> dispatchOn(@Nonnull final Environment environment) {
		return dispatchOn(environment, environment.getDefaultDispatcher());
	}
	/**
	 * Assign a new Dispatcher to handle upstream request to the returned Stream.
	 *
	 * @param environment the environment to get dispatcher from {@link reactor.core.Environment#getDefaultDispatcher()}
	 * @return a new {@link Stream} whom requests are running on a different {@link Dispatcher}
	 */
	public final Stream<O> requestOn(@Nonnull final Environment environment) {
		return requestOn(environment.getDefaultDispatcher());
	}

	/**
	 * Assign a new Dispatcher to the returned Stream. If the dispatcher is different, the new action will take
	 * care of buffering incoming data into a StreamSubscription. Otherwise default behavior is picked:
	 * FireHose synchronous subscription is the parent stream != null
	 *
	 * @param dispatcher the new dispatcher
	 * @return a new {@link Stream} running on a different {@link Dispatcher}
	 */
	public final Stream<O> dispatchOn(@Nonnull final Dispatcher dispatcher) {
		return dispatchOn(null, dispatcher);
	}


	/**
	 * Assign a new Dispatcher to handle upstream request to the returned Stream.
	 *
	 * @param currentDispatcher the new dispatcher
	 * @return a new {@link Stream} whom request are running on a different {@link Dispatcher}
	 */
	public final Stream<O> requestOn(@Nonnull final Dispatcher currentDispatcher) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher _dispatcher) {
				return new Action<O, O>(_dispatcher) {
					@Override
					public void requestMore(long n) {
						currentDispatcher.dispatch(n, upstreamSubscription, null);
					}

					@Override
					protected void doNext(O ev) {
						broadcastNext(ev);
					}
				};
			}
		});
	}

	/**
	 * Assign the a new Dispatcher and an Environment to the returned Stream. If the dispatcher is different,
	 * the new action will take
	 * care of buffering incoming data into a StreamSubscription. Otherwise default behavior is picked:
	 * FireHose synchronous subscription is the parent stream != null
	 *
	 * @param dispatcher  the new dispatcher
	 * @param environment the environment
	 * @return a new {@link Stream} running on a different {@link Dispatcher}
	 */
	public Stream<O> dispatchOn(final Environment environment, @Nonnull final Dispatcher dispatcher) {
		Assert.state(dispatcher.supportsOrdering(), "Dispatcher provided doesn't support event ordering. " +
				" For concurrent consume, refer to #partition()/groupBy() method and assign individual single dispatchers. ");

		final long capacity = dispatcher.backlogSize() != Long.MAX_VALUE ?
				dispatcher.backlogSize() - Action.RESERVED_SLOTS :
				Long.MAX_VALUE;

		return new Stream<O>() {
			@Override
			public void subscribe(Subscriber<? super O> s) {
				Stream.this.subscribe(s);
			}

			@Override
			public Dispatcher getDispatcher() {
				return dispatcher;
			}

			@Override
			public Environment getEnvironment() {
				return environment;
			}

			@Override
			public long getCapacity() {
				return capacity;
			}
		};
	}


	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe any values accepted by this {@code
	 * Stream}.
	 *
	 * @param consumer the consumer to invoke on each value
	 * @return {@literal new Stream}
	 * @since 2.0
	 */
	public final Stream<O> observe(@Nonnull final Consumer<? super O> consumer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new CallbackAction<O>(getDispatcher(), consumer, null);
			}
		});
	}


	/**
	 * Attach a {@link java.util.logging.Logger} to this {@code Stream} that will observe any signal emitted.
	 *
	 * @return {@literal new Stream}
	 * @since 2.0
	 */
	public final Stream<O> log() {
		return log(null);
	}

	/**
	 * Attach a {@link java.util.logging.Logger} to this {@code Stream} that will observe any signal emitted.
	 *
	 * @param name The logger name
	 * @return {@literal new Stream}
	 * @since 2.0
	 */
	public final Stream<O> log(final String name) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new LoggerAction<O>(dispatcher, name);
			}
		});
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe any complete signal
	 *
	 * @param consumer the consumer to invoke on complete
	 * @return {@literal a new stream}
	 * @since 2.0
	 */
	public final Stream<O> observeComplete(@Nonnull final Consumer<Void> consumer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new CallbackAction<O>(dispatcher, null, consumer);
			}
		});
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe any subscribe signal
	 *
	 * @param consumer the consumer to invoke ont subscribe
	 * @return {@literal a new stream}
	 * @since 2.0
	 */
	public final Stream<O> observeSubscribe(@Nonnull final Consumer<? super Subscriber<? super O>> consumer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new StreamStateCallbackAction<O>(dispatcher, consumer, null);
			}
		});
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe any cancel signal
	 *
	 * @param consumer the consumer to invoke on cancel
	 * @return {@literal a new stream}
	 * @since 2.0
	 */
	public final Stream<O> observeCancel(@Nonnull final Consumer<Void> consumer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new StreamStateCallbackAction<O>(dispatcher, null, consumer);
			}
		});
	}

	/**
	 * Connect an error-proof action that will ignore any error to the downstream consumers.
	 *
	 * @return a new fail-proof {@link Stream}
	 */
	public Stream<O> ignoreErrors() {
		return ignoreErrors(Predicates.always());
	}

	/**
	 * Connect an error-proof action based on the given predicate matching the current error.
	 *
	 * @param ignorePredicate a predicate to test if an error should be ignored and not passed to the consumers.
	 * @return a new fail-proof {@link Stream}
	 */
	public <E> Stream<O> ignoreErrors(final Predicate<? super Throwable> ignorePredicate) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new IgnoreErrorAction<O>(dispatcher, ignorePredicate);
			}
		});
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe terminal signal complete|error. It will pass
	 * the current {@link Action} for introspection/utility.
	 *
	 * @param consumer the consumer to invoke on terminal signal
	 * @return {@literal new Stream}
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <E extends Stream<O>> Stream<O> finallyDo(final Consumer<? super E> consumer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new FinallyAction<O, E>(dispatcher, (E) Stream.this, consumer);
			}
		});
	}

	/**
	 * Create an operation that returns the passed value if the Stream has completed without any emitted signals.
	 *
	 * @param defaultValue the value to forward if the stream is empty
	 * @return {@literal new Stream}
	 * @since 2.0
	 */
	public final Stream<O> defaultIfEmpty(final O defaultValue) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new DefaultIfEmptyAction<O>(dispatcher, defaultValue);
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code V} and pass it into
	 * another {@code Stream}.
	 *
	 * @param fn  the transformation function
	 * @param <V> the type of the return value of the transformation function
	 * @return a new {@link Stream} containing the transformed values
	 */
	public final <V> Stream<V> map(@Nonnull final Function<? super O, V> fn) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends V>>() {
			@Override
			public Action<? super O, ? extends V> apply(Dispatcher dispatcher) {
				return new MapAction<O, V>(fn, dispatcher);
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code Stream<O,V>} and pass
	 * it into another {@code Stream}.
	 *
	 * @param fn  the transformation function
	 * @param <V> the type of the return value of the transformation function
	 * @return a new {@link Stream} containing the transformed values
	 * @since 1.1, 2.0
	 */
	public final <V> Stream<V> flatMap(@Nonnull final Function<? super O,
			? extends Publisher<? extends V>> fn) {
		return map(fn).merge();
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code Stream<O,V>} and pass
	 * it into another {@code Stream}. The produced stream will emit the data from the most recent transformed stream.
	 *
	 * @param fn  the transformation function
	 * @param <V> the type of the return value of the transformation function
	 * @return a new {@link Stream} containing the transformed values
	 * @since 1.1, 2.0
	 */
	public final <V> Stream<V> switchMap(@Nonnull final Function<? super O,
			? extends Publisher<? extends V>> fn) {
		return map(fn).lift(new Function<Dispatcher, Action<Publisher<? extends V>, ? extends V>>() {
			@Override
			public Action<Publisher<? extends V>, ? extends V> apply(Dispatcher dispatcher) {
				return new SwitchAction<V>(dispatcher);
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code Stream<O,V>} and pass
	 * it into another {@code Stream}. The produced stream will emit the data from all transformed streams in order.
	 *
	 * @param fn  the transformation function
	 * @param <V> the type of the return value of the transformation function
	 * @return a new {@link Stream} containing the transformed values
	 * @since 1.1, 2.0
	 */
	public final <V> Stream<V> concatMap(@Nonnull final Function<? super O,
			? extends Publisher<? extends V>> fn) {
		return map(fn).lift(new Function<Dispatcher, Action<Publisher<? extends V>, ? extends V>>() {
			@Override
			public Action<Publisher<? extends V>, ? extends V> apply(Dispatcher dispatcher) {
				return new DynamicMergeAction<V, V>(dispatcher,
						new ConcatAction<V>(dispatcher, null));
			}
		});
	}

	/**
	 * {@link this#lift(Function)} all the nested {@link Publisher} values to a new {@link Stream}.
	 * Dynamic merge requires use of reactive-pull
	 * offered by default StreamSubscription. If merge hasn't getCapacity() to take new elements because its {@link
	 * this#getCapacity()(long)} instructed so, the subscription will buffer
	 * them.
	 *
	 * @param <V> the inner stream flowing data type that will be the produced signal.
	 * @return the merged stream
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <V> Stream<V> merge() {
		return fanIn(null);
	}

	/**
	 * {@link this#lift(Function)} all the nested {@link Publisher} values from this current upstream and from the
	 * passed publisher.
	 *
	 * @return the merged stream
	 * @since 2.0
	 */
	public final Stream<O> mergeWith(final Publisher<? extends O> publisher) {
		return new Stream<O>(){
			@Override
			public void subscribe(Subscriber<? super O> s) {
				new MergeAction<>(Stream.this.getDispatcher(), Arrays.asList(Stream.this, publisher))
						.env(Stream.this.getEnvironment())
						.subscribe(s);
			}
		};
	}

	/**
	 * {@link this#lift(Function)} all the nested {@link Publisher} values from this current upstream and then on
	 * complete consume from the
	 * passed publisher.
	 *
	 * @return the merged stream
	 * @since 2.0
	 */
	public final Stream<O> concatWith(final Publisher<? extends O> publisher) {
		return new Stream<O>(){
			@Override
			public void subscribe(Subscriber<? super O> s) {
				new ConcatAction<>(Stream.this.getDispatcher(), Arrays.asList(Stream.this, publisher))
						.env(Stream.this.getEnvironment())
						.subscribe(s);
			}
		};
	}

	/**
	 * {@link this#lift(Function)} all the nested {@link Publisher} values to a new {@link Stream} until one of them
	 * complete.
	 * The result will be produced with a list of each upstream most recent emitted data.
	 *
	 * @return the zipped and joined stream
	 * @since 2.0
	 */
	public final <V> Stream<List<V>> join() {
		return zip(ZipAction.<TupleN, V>joinZipper());
	}

	/**
	 * {@link this#lift(Function)} all the nested {@link Publisher} values to a new {@link Stream} until one of them
	 * complete.
	 * The result will be produced with a list of each upstream most recent emitted data.
	 *
	 * @return the zipped and joined stream
	 * @since 2.0
	 */
	public final <V> Stream<List<V>> joinWith(Publisher<? extends V> publisher) {
		return zipWith(publisher, ZipAction.<Tuple2<O, V>, V>joinZipper());
	}


	/**
	 * {@link this#lift(Function)} all the nested {@link Publisher} values to a new {@link Stream} until one of them
	 * complete.
	 * The result will be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the merged stream
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <V> Stream<V> zip(final @Nonnull Function<TupleN, ? extends V> zipper) {
		final Stream<Publisher<?>> thiz = (Stream<Publisher<?>>) this;

		return thiz.lift(new Function<Dispatcher, Action<Publisher<?>, V>>() {
			@Override
			public Action<Publisher<?>, V> apply(Dispatcher dispatcher) {
				return new DynamicMergeAction<Object, V>(getDispatcher(),
						new ZipAction<Object, V, TupleN>(getDispatcher(), zipper, null)).
						capacity(getCapacity()).env(getEnvironment());
			}
		});
	}

	/**
	 * {@link this#lift(Function)} with the passed {@link Publisher} values to a new {@link Stream} until one of them
	 * complete.
	 * The result will be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the zipped stream
	 * @since 2.0
	 */
	public final <T2, V> Stream<V> zipWith(final Publisher<? extends T2> publisher,
	                                       final @Nonnull Function<Tuple2<O, T2>, V> zipper) {
		return new Stream<V>(){
			@Override
			public void subscribe(Subscriber<? super V> s) {
				new ZipAction<>(Stream.this.getDispatcher(), zipper, Arrays.asList(Stream.this, publisher))
						.env(Stream.this.getEnvironment())
						.subscribe(s);
			}
		};
	}

	/**
	 * {@link this#lift(Function)} all the nested {@link Publisher} values to a new {@link Stream} until one of them
	 * complete.
	 * The result will be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the zipped stream
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <T2, V> Stream<V> zipWith(Iterable<? extends T2> iterable,
	                                       @Nonnull Function<Tuple2<O, T2>, V> zipper) {
		return zipWith(Streams.from(iterable), zipper);
	}

	/**
	 * {@link this#lift(Function)} all the nested {@link Publisher} values to a new {@link Stream} calling the logic
	 * inside the provided fanInAction for complex merging strategies.
	 * {@link reactor.rx.action.FanInAction} provides helpers to create subscriber for each source,
	 * a registry of incoming sources and overriding doXXX signals as usual to produce the result via
	 * reactor.rx.action.Action#broadcastXXX.
	 * <p>
	 * A default fanInAction will act like {@link this#merge()}, passing values to doNext. In java8 one can then
	 * implement
	 * stream.fanIn(data -> broadcastNext(data)) or stream.fanIn(System.out::println)
	 * <p>
	 * <p>
	 * Dynamic merge (moving nested data into the top-level returned stream) requires use of reactive-pull offered by
	 * default StreamSubscription. If merge hasn't getCapacity() to
	 * take new elements because its {@link
	 * this#getCapacity()(long)} instructed so, the subscription will buffer
	 * them.
	 *
	 * @param <T> the nested type of flowing upstream Stream.
	 * @param <V> the produced output
	 * @return the zipped stream
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public <T, V> Stream<V> fanIn(
			final FanInAction<T, ?, V, ? extends FanInAction.InnerSubscriber<T, ?, V>> fanInAction
	) {
		final Stream<Publisher<T>> thiz = (Stream<Publisher<T>>) this;

		return thiz.lift(new Function<Dispatcher, Action<Publisher<? extends T>, V>>() {
			@Override
			public Action<Publisher<? extends T>, V> apply(Dispatcher dispatcher) {
				return new DynamicMergeAction<T, V>(getDispatcher(), fanInAction).
						capacity(getCapacity()).env(getEnvironment());
			}
		});
	}

	/**
	 * Bind the stream to a given {@param elements} volume of in-flight data:
	 * - An {@link Action} will request up to the defined volume upstream.
	 * - An {@link Action} will track the pending requests and fire up to {@param elements} when the previous volume has
	 * been processed.
	 * - A {@link BatchAction} and any other size-bound action will be limited to the defined volume.
	 * <p>
	 * <p>
	 * A stream capacity can't be superior to the underlying dispatcher capacity: if the {@param elements} overflow the
	 * dispatcher backlog size, the capacity will be aligned automatically to fit it.
	 * RingBufferDispatcher will for instance take to a power of 2 size up to {@literal Integer.MAX_VALUE},
	 * where a Stream can be sized up to {@literal Long.MAX_VALUE} in flight data.
	 * <p>
	 * <p>
	 * When the stream receives more elements than requested, incoming data is eventually staged in a {@link
	 * org.reactivestreams.Subscription}.
	 * The subscription can react differently according to the implementation in-use,
	 * the default strategy is as following:
	 * - The first-level of pair compositions Stream->Action will overflow data in a {@link reactor.queue
	 * .CompletableQueue},
	 * ready to be polled when the action fire the pending requests.
	 * - The following pairs of Action->Action will synchronously pass data
	 * - Any pair of Stream->Subscriber or Action->Subscriber will behave as with the root Stream->Action pair rule.
	 * - {@link this#onOverflowBuffer()} force this staging behavior, with a possibilty to pass a {@link reactor.queue
	 * .PersistentQueue}
	 *
	 * @param elements maximum number of in-flight data
	 * @return a backpressure capable stream
	 */
	public Stream<O> capacity(long elements) {
		return onOverflowBuffer().capacity(elements);
	}

	/**
	 * Attach a No-Op Action that only serves the purpose of buffering incoming values if not enough demand is signaled
	 * downstream. A buffering capable stream will prevent underlying dispatcher to be saturated (and sometimes
	 * blocking).
	 *
	 * @return a buffered stream
	 * @since 2.0
	 */
	public final Stream<O> onOverflowBuffer() {
		return onOverflowBuffer(new Supplier<CompletableQueue<O>>() {
			@Override
			public CompletableQueue<O> get() {
				return new CompletableLinkedQueue<O>();
			}
		});
	}

	/**
	 * Attach a No-Op Action that only serves the purpose of buffering incoming values if not enough demand is signaled
	 * downstream. A buffering capable stream will prevent underlying dispatcher to be saturated (and sometimes
	 * blocking).
	 *
	 * @param queueSupplier A completable queue {@link reactor.function.Supplier} to provide support for overflow
	 * @return a buffered stream
	 * @since 2.0
	 */
	public Stream<O> onOverflowBuffer(final Supplier<? extends CompletableQueue<O>> queueSupplier) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new FlowControlAction<O>(dispatcher, queueSupplier);
			}
		});
	}

	/**
	 * Attach a No-Op Action that only serves the purpose of dropping incoming values if not enough demand is signaled
	 * downstream. A dropping stream will prevent underlying dispatcher to be saturated (and sometimes
	 * blocking).
	 *
	 * @return a dropping stream
	 * @since 2.0
	 */
	public final Stream<O> onOverflowDrop() {
		return onOverflowBuffer(null);
	}

	/**
	 * Evaluate each accepted value against the given {@link Predicate}. If the predicate test succeeds, the value is
	 * passed into the new {@code Stream}. If the predicate test fails, the value is ignored.
	 *
	 * @param p the {@link Predicate} to test values against
	 * @return a new {@link Stream} containing only values that pass the predicate test
	 */
	public final Stream<O> filter(final Predicate<? super O> p) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new FilterAction<O>(p, dispatcher);
			}
		});
	}

	/**
	 * Evaluate each accepted boolean value. If the predicate test succeeds, the value is
	 * passed into the new {@code Stream}. If the predicate test fails, the value is ignored.
	 *
	 * @return a new {@link Stream} containing only values that pass the predicate test
	 * @since 1.1, 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<Boolean> filter() {
		return ((Stream<Boolean>) this).filter(FilterAction.simplePredicate);
	}


	/**
	 * Create a new {@code Stream} whose only value will be the current instance of the {@link Stream}.
	 *
	 * @return a new {@link Stream} whose only value will be the materialized current {@link Stream}
	 * @since 2.0
	 */
	public final Stream<Stream<O>> nest() {
		return Streams.just(this);
	}


	/**
	 * Create a new {@code Stream} whose will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@literal Integer.MAX_VALUE}.
	 *
	 * @return a new fault-tolerant {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> retry() {
		return retry(Integer.MAX_VALUE);
	}

	/**
	 * Create a new {@code Stream} whose will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@param numRetries}.
	 * This is generally useful for retry strategies and fault-tolerant streams.
	 *
	 * @param numRetries the number of times to tolerate an error
	 * @return a new fault-tolerant {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> retry(int numRetries) {
		return retry(numRetries, null);
	}

	/**
	 * Create a new {@code Stream} whose will re-subscribe its oldest parent-child stream pair.
	 * {@param retryMatcher} will test an incoming {@link Throwable}, if positive the retry will occur.
	 * This is generally useful for retry strategies and fault-tolerant streams.
	 *
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 * @return a new fault-tolerant {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> retry(Predicate<Throwable> retryMatcher) {
		return retry(Integer.MAX_VALUE, retryMatcher);
	}

	/**
	 * Create a new {@code Stream} whose will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@param numRetries}. {@param retryMatcher} will test an incoming {@Throwable},
	 * if positive
	 * the retry will occur (in conjonction with the {@param numRetries} condition).
	 * This is generally useful for retry strategies and fault-tolerant streams.
	 *
	 * @param numRetries   the number of times to tolerate an error
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 * @return a new fault-tolerant {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> retry(final int numRetries, final Predicate<Throwable> retryMatcher) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new RetryAction<O>(dispatcher, numRetries, retryMatcher, Stream.this);
			}
		});
	}


	/**
	 * Create a new {@code Stream} whose will re-subscribe its oldest parent-child stream pair if the backOff stream
	 * produced by the passed mapper emits any next data or complete signal. It will propagate the error if the backoff
	 * stream emits an error signal.
	 *
	 * @param backOffStream the function taking the error stream as an input and returning a new stream that applies
	 *                       some backoff policy e.g. Streams.timer
	 * @return a new fault-tolerant {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> retryWhen(final Function<Stream<? extends Throwable>, ? extends Publisher<?>> backOffStream) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new RetryWhenAction<O>(dispatcher, backOffStream, Stream.this);
			}
		});
	}

	/**
	 * Create a new {@code Stream} that will signal next elements up to {@param max} times.
	 *
	 * @param max the number of times to broadcast next signals before completing
	 * @return a new limited {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> take(long max) {
		return takeWhile(max, null);
	}

	/**
	 * Create a new {@code Stream} that will signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to broadcast next signals before completing
	 * @param unit the time unit to use
	 * @return a new limited {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> take(long time, TimeUnit unit) {
		return take(time, unit, getTimer());
	}

	/**
	 * Create a new {@code Stream} that will signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to broadcast next signals before completing
	 * @param unit the time unit to use
	 * @param timer the Timer to use
	 * @return a new limited {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> take(final long time, final TimeUnit unit, final Timer timer) {
		if(time > 0) {
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the stream");
			return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
				@Override
				public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
					return new TakeUntilTimeout<O>(dispatcher, time, unit, timer);
				}
			});
		}else{
			return Streams.empty();
		}
	}

	/**
	 * Create a new {@code Stream} that will signal next elements while {@param limitMatcher} is true.
	 *
	 * @param limitMatcher the predicate to evaluate for stop broadcasting events
	 * @return a new limited {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> takeWhile(Predicate<O> limitMatcher) {
		return takeWhile(Long.MAX_VALUE, limitMatcher);
	}

	/**
	 * Create a new {@code Stream} that will signal next elements while {@param limitMatcher} is true or
	 * up to {@param max} times.
	 *
	 * @param max          the number of times to broadcast next signals before dropping
	 * @param limitMatcher the predicate to evaluate for starting dropping events and completing
	 * @return a new limited {@code Stream}
	 * @since 2.0
	 */
	public final Stream<O> takeWhile(final long max, final Predicate<O> limitMatcher) {
		if(max > 0) {
			return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
				@Override
				public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
					return new TakeAction<O>(dispatcher, limitMatcher, max);
				}
			});
		}else{
			return Streams.empty();
		}
	}

	/**
	 * Create a new {@code Stream} that accepts a {@link reactor.tuple.Tuple2} of T1 {@link Long} system time in millis
	 * and T2 {@link
	 * <T>}
	 * associated data
	 *
	 * @return a new {@link Stream} that emits tuples of nano time and matching data
	 * @since 2.0
	 */
	public final Stream<Tuple2<Long, O>> timestamp() {
		return lift(new Function<Dispatcher, Action<? super O, ? extends Tuple2<Long, O>>>() {
			@Override
			public Action<? super O, ? extends Tuple2<Long, O>> apply(Dispatcher dispatcher) {
				return new TimestampAction<O>(dispatcher);
			}
		});
	}

	/**
	 * Create a new {@code Stream} that accepts a {@link reactor.tuple.Tuple2} of T1 {@link Long} nanotime and T2 {@link
	 * <T>}
	 * associated data. The timemillis corresponds to the elapsed time between the subscribe and the first next
	 * signals OR
	 * between two next signals.
	 *
	 * @return a new {@link Stream} that emits tuples of nano time and matching data
	 * @since 2.0
	 */
	public final Stream<Tuple2<Long, O>> elapsed() {
		return lift(new Function<Dispatcher, Action<? super O, ? extends Tuple2<Long, O>>>() {
			@Override
			public Action<? super O, ? extends Tuple2<Long, O>> apply(Dispatcher dispatcher) {
				return new ElapsedAction<O>(dispatcher);
			}
		});
	}

	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch. Requires a {@code
	 * getCapacity()} to have been set.
	 * <p>
	 * When a new batch is triggered, the first value of that next batch will be pushed into this {@code Stream}.
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst() {
		return sampleFirst((int) Math.min(Integer.MAX_VALUE, getCapacity()));
	}

	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch.
	 * <p>
	 * When a new batch is triggered, the first value of that next batch will be pushed into this {@code Stream}.
	 *
	 * @param batchSize the batch size to use
	 * @return a new {@link Stream} whose values are the first value of each batch)
	 */
	public final Stream<O> sampleFirst(final int batchSize) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new SampleAction<O>(dispatcher, batchSize, true);
			}
		});
	}


	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst(long timespan, TimeUnit unit) {
		return sampleFirst(timespan, unit, getTimer());
	}


	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @param timer    the Timer to run on
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst(long timespan, TimeUnit unit, Timer timer) {
		return sampleFirst(Integer.MAX_VALUE, timespan, unit, timer);
	}


	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch.
	 *
	 * @param maxSize  the max counted size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst(int maxSize, long timespan, TimeUnit unit) {
		return sampleFirst(maxSize, timespan, unit, getTimer());
	}


	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch.
	 *
	 * @param maxSize  the max counted size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @param timer    the Timer to run on
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst(final int maxSize, final long timespan, final TimeUnit unit, final Timer timer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new SampleAction<O>(dispatcher, true, maxSize, timespan, unit, timer);
			}
		});
	}

	/**
	 * Create a new {@code Stream} whose values will be only the last value of each batch. Requires a {@code
	 * getCapacity()}
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> sample() {
		return sample((int) Math.min(Integer.MAX_VALUE, getCapacity()));
	}


	/**
	 * Create a new {@code Stream} whose values will be only the last value of each batch. Requires a {@code
	 * getCapacity()}
	 *
	 * @param batchSize the batch size to use
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> sample(final int batchSize) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new SampleAction<O>(dispatcher, batchSize);
			}
		});
	}


	/**
	 * Create a new {@code Stream} whose values will be only the last value of each batch.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> sample(long timespan, TimeUnit unit) {
		return sample(timespan, unit, getTimer());
	}


	/**
	 * Create a new {@code Stream} whose values will be only the last value of each batch.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @param timer    the Timer to run on
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> sample(long timespan, TimeUnit unit, Timer timer) {
		return sample(Integer.MAX_VALUE, timespan, unit, timer);
	}


	/**
	 * Create a new {@code Stream} whose values will be only the last value of each batch.
	 *
	 * @param maxSize  the max counted size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> sample(int maxSize, long timespan, TimeUnit unit) {
		return sample(maxSize, timespan, unit, getEnvironment() == null ? Environment.timer() : getEnvironment().getTimer
				());
	}


	/**
	 * Create a new {@code Stream} whose values will be only the last value of each batch.
	 *
	 * @param maxSize  the max counted size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @param timer    the Timer to run on
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> sample(final int maxSize, final long timespan, final TimeUnit unit, final Timer timer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new SampleAction<O>(dispatcher, false, maxSize, timespan, unit, timer);
			}
		});
	}

	/**
	 * Create a new {@code Stream} that filters out consecutive equals values.
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 * @since 2.0
	 */
	public final Stream<O> distinctUntilChanged() {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new DistinctUntilChangedAction<O>(dispatcher);
			}
		});
	}

	/**
	 * Create a new {@code Stream} whose values will be each element E of any Iterable<E> flowing this Stream
	 * When a new batch is triggered, the last value of that next batch will be pushed into this {@code Stream}.
	 *
	 * @return a new {@link Stream} whose values result from the iterable input
	 * @since 1.1, 2.0
	 */
	public final <V> Stream<V> split() {
		return split(Long.MAX_VALUE);
	}

	/**
	 * Create a new {@code Stream} whose values will be each element E of any Iterable<E> flowing this Stream
	 * <p>
	 * When a new batch is triggered, the last value of that next batch will be pushed into this {@code Stream}.
	 *
	 * @param batchSize the batch size to use
	 * @return a new {@link Stream} whose values result from the iterable input
	 * @since 1.1, 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <V> Stream<V> split(final long batchSize) {
		final Stream<Iterable<V>> iterableStream = (Stream<Iterable<V>>) this;
		/*return iterableStream.flatMap(new Function<Iterable<V>, Publisher<? extends V>>() {
			@Override
			public Publisher<? extends V> apply(Iterable<V> vs) {
				return Streams.from(vs);
			}
		});*/
		return iterableStream.lift(new Function<Dispatcher, Action<? super Iterable<V>, ? extends V>>() {
			@Override
			public Action<? super Iterable<V>, ? extends V> apply(Dispatcher dispatcher) {
				return new SplitAction<V>(dispatcher).capacity(batchSize);
			}
		});
	}

	/**
	 * Collect incoming values into a {@link java.util.List} that will be pushed into the returned {@code Stream} every
	 * time {@code
	 * getCapacity()} or flush is triggered has been reached.
	 *
	 * @return a new {@link Stream} whose values are a {@link java.util.List} of all values in this batch
	 */
	public final Stream<List<O>> buffer() {
		return buffer((int) Math.min(Integer.MAX_VALUE, getCapacity()));
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every time {@code
	 * getCapacity()} has been reached.
	 *
	 * @param maxSize the collected size
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(final int maxSize) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends List<O>>>() {
			@Override
			public Action<? super O, ? extends List<O>> apply(Dispatcher dispatcher) {
				return new BufferAction<O>(dispatcher, maxSize);
			}
		});
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every
	 * timespan.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(long timespan, TimeUnit unit) {
		return buffer(timespan, unit, getTimer());
	}


	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every
	 * timespan.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @param timer    the Timer to run on
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(long timespan, TimeUnit unit, Timer timer) {
		return buffer(Integer.MAX_VALUE, timespan, unit, timer);
	}


	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every
	 * timespan OR maxSize items.
	 *
	 * @param maxSize  the max collected size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(int maxSize, long timespan, TimeUnit unit) {
		return buffer(maxSize, timespan, unit, getTimer());
	}


	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every
	 * timespan OR maxSize items
	 *
	 * @param maxSize  the max collected size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @param timer    the Timer to run on
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(final int maxSize, final long timespan, final TimeUnit unit, final Timer timer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends List<O>>>() {
			@Override
			public Action<? super O, ? extends List<O>> apply(Dispatcher dispatcher) {
				return new BufferAction<O>(dispatcher, maxSize, timespan, unit, timer);
			}
		});
	}


	/**
	 * Collect incoming values into an internal array, providing a {@link List} that will be pushed into the returned
	 * {@code Stream}. The buffer will retain up to the last {@param backlog} elements in memory.
	 *
	 * @param backlog maximum amount of items to keep
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this buffer
	 * @since 2.0
	 */
	public final Stream<List<O>> movingBuffer(final int backlog) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends List<O>>>() {
			@Override
			public Action<? super O, ? extends List<O>> apply(Dispatcher dispatcher) {
				return new MovingBufferAction<O>(dispatcher, backlog, 1);
			}
		});
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue<O>} that will be re-ordered and signaled to the
	 * returned
	 * fresh {@link Stream}. Possible flush triggers are: {@link this#getCapacity()},
	 * complete signal or request signal.
	 * PriorityQueue will use the {@link Comparable<O>} interface from an incoming data signal.
	 *
	 * @return a new {@link Stream} whose values re-ordered using a PriorityQueue.
	 * @since 2.0
	 */
	public final Stream<O> sort() {
		return sort(null);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue<O>} that will be re-ordered and signaled to the
	 * returned
	 * fresh {@link Stream}. Possible flush triggers are: {@param maxCapacity}, complete signal or request signal.
	 * PriorityQueue will use the {@link Comparable<O>} interface from an incoming data signal.
	 *
	 * @param maxCapacity a fixed maximum number or elements to re-order at once.
	 * @return a new {@link Stream} whose values re-ordered using a PriorityQueue.
	 * @since 2.0
	 */
	public final Stream<O> sort(int maxCapacity) {
		return sort(maxCapacity, null);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue<O>} that will be re-ordered and signaled to the
	 * returned
	 * fresh {@link Stream}. Possible flush triggers are: {@link this#getCapacity()},
	 * complete signal or request signal.
	 * PriorityQueue will use the {@link Comparable<O>} interface from an incoming data signal.
	 *
	 * @param comparator A {@link Comparator<O>} to evaluate incoming data
	 * @return a new {@link Stream} whose values re-ordered using a PriorityQueue.
	 * @since 2.0
	 */
	public final Stream<O> sort(Comparator<? super O> comparator) {
		return sort((int) Math.min(Integer.MAX_VALUE, getCapacity()), comparator);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue<O>} that will be re-ordered and signaled to the
	 * returned
	 * fresh {@link Stream}. Possible flush triggers are: {@param maxCapacity}, complete signal or request signal.
	 * PriorityQueue will use the {@link Comparable<O>} interface from an incoming data signal.
	 *
	 * @param maxCapacity a fixed maximum number or elements to re-order at once.
	 * @param comparator  A {@link Comparator<O>} to evaluate incoming data
	 * @return a new {@link Stream} whose values re-ordered using a PriorityQueue.
	 * @since 2.0
	 */
	public final Stream<O> sort(final int maxCapacity, final Comparator<? super O> comparator) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new SortAction<O>(dispatcher, maxCapacity, comparator);
			}
		});
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined {@link this#getCapacity()}
	 * times. The nested streams will be pushed into the returned {@code Stream}.
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window() {
		return window((int) Math.min(Integer.MAX_VALUE, getCapacity()));
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined {@param backlog} times.
	 * The nested streams will be pushed into the returned {@code Stream}.
	 *
	 * @param backlog the time period when each window close and flush the attached consumer
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(final int backlog) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends Stream<O>>>() {
			@Override
			public Action<? super O, ? extends Stream<O>> apply(Dispatcher dispatcher) {
				return new WindowAction<O>(dispatcher, backlog);
			}
		});
	}


	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan.
	 * The nested streams will be pushed into the returned {@code Stream}.
	 *
	 * @param timespan the period in unit to use to release a new window as a Stream
	 * @param unit     the time unit
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(long timespan, TimeUnit unit) {
		return window(timespan, unit, getTimer());
	}


	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan.
	 * The nested streams will be pushed into the returned {@code Stream}.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @param timer    the Timer to run on
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(long timespan, TimeUnit unit, Timer timer) {
		return window(Integer.MAX_VALUE, timespan, unit, timer);
	}


	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan OR maxSize items.
	 * The nested streams will be pushed into the returned {@code Stream}.
	 *
	 * @param maxSize  the max collected size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(int maxSize, long timespan, TimeUnit unit) {
		return window(maxSize, timespan, unit, getEnvironment() == null ? Environment.timer() : getEnvironment().getTimer
				());
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan OR maxSize items.
	 * The nested streams will be pushed into the returned {@code Stream}.
	 *
	 * @param maxSize  the max collected size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit     the time unit
	 * @param timer    the Timer to run on
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(final int maxSize, final long timespan, final TimeUnit unit, final Timer
			timer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends Stream<O>>>() {
			@Override
			public Action<? super O, ? extends Stream<O>> apply(Dispatcher dispatcher) {
				return new WindowAction<O>(dispatcher, maxSize, timespan, unit, timer);
			}
		});
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the
	 * {param keyMapper}.
	 *
	 * @param keyMapper the key mapping function that evaluates an incoming data and returns a key.
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 * @since 2.0
	 */
	public final <K> Stream<GroupedStream<K, O>> groupBy(final Function<? super O, ? extends K> keyMapper) {
		return lift(new Function<Dispatcher, Action<? super O, GroupedStream<K, O>>>() {
			@Override
			public Action<? super O, GroupedStream<K, O>> apply(Dispatcher dispatcher) {
				return new GroupByAction<>(keyMapper, dispatcher);
			}
		});
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the
	 * {param keyMapper}. The hashcode of the incoming data will be used for partitioning over {@link Environment#PROCESSORS} buckets.
	 * That means that at any point of time at most {@link Environment#PROCESSORS} number of streams will be created and used accordingly
	 * to the current hashcode % n result.
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values routed to this partition
	 * @since 2.0
	 */
	public final Stream<GroupedStream<Integer, O>> partition() {
		return partition(Environment.PROCESSORS);
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the
	 * {param keyMapper}. The hashcode of the incoming data will be used for partitioning over the buckets number passed.
	 * That means that at any point of time at most {@code buckets} number of streams will be created and used accordingly
	 * to the current hashcode % buckets result.
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 * @since 2.0
	 */
	public final Stream<GroupedStream<Integer, O>> partition(final int buckets) {
		return groupBy(new Function<O, Integer>() {
			//volatile int cursor = -1;
			@Override
			public Integer apply(O o) {
				return o.hashCode() % buckets;
				/*int key =  ++cursor % buckets;
				if(cursor == buckets){
					cursor = 0;
				}
				return key;*/
			}
		});
	}

	/**
	 * Reduce the values passing through this {@code Stream} into an object {@code A}. The given initial object will be
	 * passed to the function's {@link reactor.tuple.Tuple2} argument.
	 *
	 * @param fn      the reduce function
	 * @param initial the initial argument to pass to the reduce function
	 * @param <A>     the type of the reduced object
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 */
	public final <A> Stream<A> reduce(A initial, @Nonnull Function<Tuple2<O, A>, A> fn) {
		return reduce(Functions.supplier(initial), (int) Math.min(Integer.MAX_VALUE, getCapacity()), fn);
	}

	/**
	 * Reduce the values passing through this {@code Stream} into an object {@code A}. The given {@link Supplier} will be
	 * used to produce initial accumulator objects either on the first reduce call,
	 * in the case of an unbounded {@code
	 * Stream}, or on the first value of each batch, if a {@code getCapacity()} is set.
	 * <p>
	 * In an unbounded {@code Stream}, the accumulated value will be published on the returned {@code Stream} on flush
	 * only. But when a {@code getCapacity()} has been, the accumulated
	 * value will only be published on the new {@code Stream} at the end of each batch. On the next value (the
	 * first of
	 * the next batch), the {@link Supplier} is called again for a new accumulator object and the reduce starts over with
	 * a new accumulator.
	 *
	 * @param fn           the reduce function
	 * @param accumulators the {@link Supplier} that will provide accumulators
	 * @param batchSize    the batch size to use
	 * @param <A>          the type of the reduced object
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 */
	public final <A> Stream<A> reduce(@Nullable final Supplier<A> accumulators,
	                                  final int batchSize,
	                                  @Nonnull final Function<Tuple2<O, A>, A> fn
	) {

		return lift(new Function<Dispatcher, Action<? super O, ? extends A>>() {
			@Override
			public Action<? super O, ? extends A> apply(Dispatcher dispatcher) {
				return new ReduceAction<O, A>(
						dispatcher,
						batchSize,
						accumulators,
						fn
				);
			}
		});
	}

	/**
	 * Reduce the values passing through this {@code Stream} into an object {@code A}.
	 *
	 * @param fn  the reduce function
	 * @param <A> the type of the reduced object
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 */
	public final <A> Stream<A> reduce(@Nonnull final Function<Tuple2<O, A>, A> fn) {
		return reduce(null, (int) Math.min(Integer.MAX_VALUE, getCapacity()), fn);
	}

	/**
	 * Scan the values passing through this {@code Stream} into an object {@code A}. The given initial object will be
	 * passed to the function's {@link Tuple2} argument. Behave like Reduce but triggers downstream Stream for every
	 * transformation.
	 *
	 * @param initial the initial argument to pass to the reduce function
	 * @param fn      the scan function
	 * @param <A>     the type of the reduced object
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 * @since 1.1, 2.0
	 */
	public final <A> Stream<A> scan(A initial, @Nonnull Function<Tuple2<O, A>, A> fn) {
		return scan(Functions.supplier(initial), fn);
	}

	/**
	 * Scan the values passing through this {@code Stream} into an object {@code A}.
	 *
	 * @param fn  the reduce function
	 * @param <A> the type of the reduced object
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 * @since 1.1, 2.0
	 */
	public final <A> Stream<A> scan(@Nonnull final Function<Tuple2<O, A>, A> fn) {
		return scan((Supplier<A>) null, fn);
	}

	/**
	 * Scan the values passing through this {@code Stream} into an object {@code A}. The given {@link Supplier} will be
	 * used to produce initial accumulator objects either on the first reduce call, in the case of an unbounded
	 * {@code
	 * Stream}, or on the first value of each batch, if a {@code getCapacity()} is set.
	 * <p>
	 * The accumulated value will be published on the returned {@code Stream} every time
	 * a
	 * value is accepted.
	 *
	 * @param accumulators the {@link Supplier} that will provide accumulators
	 * @param fn           the scan function
	 * @param <A>          the type of the reduced object
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 * @since 1.1, 2.0
	 */
	public final <A> Stream<A> scan(@Nullable final Supplier<A> accumulators,
	                                @Nonnull final Function<Tuple2<O, A>, A> fn) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends A>>() {
			@Override
			public Action<? super O, ? extends A> apply(Dispatcher dispatcher) {
				return new ScanAction<O, A>(accumulators,
						fn,
						dispatcher);
			}
		});
	}

	/**
	 * Count accepted events for each batch and pass each accumulated long to the {@param stream}.
	 */
	public final Stream<Long> count() {
		return count(getCapacity());
	}

	/**
	 * Count accepted events for each batch {@param i} and pass each accumulated long to the {@param stream}.
	 *
	 * @return a new {@link Stream}
	 */
	public final Stream<Long> count(final long i) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends Long>>() {
			@Override
			public Action<? super O, ? extends Long> apply(Dispatcher dispatcher) {
				return new CountAction<O>(dispatcher, i);
			}
		});
	}

	/**
	 * Request the parent stream every {@param period} milliseconds. Timeout is run on the environment root timer.
	 *
	 * @param period the period in milliseconds between two notifications on this stream
	 * @return a new {@link Stream}
	 * @since 2.0
	 */
	public final Stream<O> throttle(long period) {
		return throttle(period, -1l);
	}

	/**
	 * Request the parent stream every {@param period} milliseconds after an initial {@param delay}.
	 * Timeout is run on the environment root timer.
	 *
	 * @param delay  the timeout in milliseconds before starting consuming
	 * @param period the period in milliseconds between two notifications on this stream
	 * @return a new {@link Stream}
	 * @since 2.0
	 */
	public final Stream<O> throttle(long period, long delay) {
		Timer timer = getTimer();
		Assert.state(timer != null, "Cannot use default timer as no environment has been provided to this " +
				"Stream");
		return throttle(period, delay, timer);
	}

	/**
	 * Request the parent stream every {@param period} milliseconds after an initial {@param delay}.
	 * Timeout is run on the given {@param timer}.
	 *
	 * @param period the timeout in milliseconds between two notifications on this stream
	 * @param delay  the timeout in milliseconds before starting consuming
	 * @param timer  the reactor timer to run the timeout on
	 * @return a new {@link Stream}
	 * @since 2.0
	 */
	public final Stream<O> throttle(final long period, final long delay, final Timer timer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new ThrottleRequestAction<O>(
						dispatcher,
						timer,
						period,
						delay
				);
			}
		});
	}

	/**
	 * Signal an error if no data has been emitted for {@param
	 * timeout} milliseconds. Timeout is run on the environment root timer.
	 * <p>
	 * A Timeout Exception will be signaled if no data or complete signal have been sent within the given period.
	 *
	 * @param timeout the timeout in milliseconds between two notifications on this composable
	 * @return a new {@link Stream}
	 * @since 1.1, 2.0
	 */
	public final Stream<O> timeout(long timeout) {
		return timeout(timeout, null);
	}

	/**
	 * Signal an error if no data has been emitted for {@param
	 * timeout} milliseconds. Timeout is run on the environment root timer.
	 * <p>
	 * A Timeout Exception will be signaled if no data or complete signal have been sent within the given period.
	 *
	 * @param timeout the timeout in unit between two notifications on this composable
	 * @param unit    the time unit
	 * @return a new {@link Stream}
	 * @since 1.1, 2.0
	 */
	public final Stream<O> timeout(long timeout, TimeUnit unit) {
		return timeout(timeout, unit, null);
	}

	/**
	 * Switch to the fallback Publisher if no data has been emitted for {@param
	 * timeout} milliseconds. Timeout is run on the environment root timer.
	 * <p>
	 * The current subscription will be cancelled and the fallback publisher subscribed.
	 * <p>
	 * A Timeout Exception will be signaled if no data or complete signal have been sent within the given period.
	 *
	 * @param timeout  the timeout in unit between two notifications on this composable
	 * @param unit     the time unit
	 * @param fallback the fallback {@link Publisher} to subscribe to once the timeout has occured
	 * @return a new {@link Stream}
	 * @since 2.0
	 */
	public final Stream<O> timeout(long timeout, TimeUnit unit, Publisher<? extends O> fallback) {
		Timer timer = getTimer();
		Assert.state(timer != null, "Cannot use default timer as no environment has been provided to this " +
				"Stream");
		return timeout(timeout, unit, fallback, timer);
	}

	/**
	 * Signal an error if no data has been emitted for {@param
	 * timeout} milliseconds. Timeout is run on the environment root timer.
	 * <p>
	 * A Timeout Exception will be signaled if no data or complete signal have been sent within the given period.
	 *
	 * @param timeout the timeout in milliseconds between two notifications on this composable
	 * @param unit    the time unit
	 * @param timer   the reactor timer to run the timeout on
	 * @return a new {@link Stream}
	 * @since 1.1, 2.0
	 */
	public final Stream<O> timeout(final long timeout, final TimeUnit unit, final Publisher<? extends O> fallback, final
	Timer timer) {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return new TimeoutAction<O>(
						dispatcher,
						fallback,
						timer,
						unit != null ? TimeUnit.MILLISECONDS.convert(timeout, unit) : timeout
				);
			}
		});
	}

	/**
	 * Combine the most ancient upstream action to act as the {@link org.reactivestreams.Subscriber} input component and
	 * the current stream to act as the {@link org.reactivestreams.Publisher}.
	 * <p>
	 * Useful to share and ship a full stream whilst hiding the staging actions in the middle.
	 * <p>
	 * Default behavior, e.g. a single stream, will raise an {@link java.lang.IllegalStateException} as there would not
	 * be any Subscriber (Input) side to combine. {@link reactor.rx.action.Action#combine()} is the usual reference
	 * implementation used.
	 *
	 * @param <E> the type of the most ancien action input.
	 * @return new Combined Action
	 */
	public <E> CombineAction<E, O> combine() {
		throw new IllegalStateException("Cannot combine a single Stream");
	}

	/**
	 * Return the promise of the next triggered signal.
	 * A promise is a container that will capture only once the first arriving error|next|complete signal
	 * to this {@link Stream}. It is useful to coordinate on single data streams or await for any signal.
	 *
	 * @return a new {@link Promise}
	 * @since 2.0
	 */
	public final Promise<O> next() {
		Promise<O> d = new Promise<O>(
				getDispatcher(),
				getEnvironment()
		);
		subscribe(d);
		return d;
	}

	/**
	 * Blocking call to eagerly fetch values from this stream
	 *
	 * @return the buffered collection
	 * @since 2.0
	 */
	public final Promise<List<O>> toList() {
		return toList(-1);
	}

	/**
	 * Return the promise of N signals collected into an array list.
	 *
	 * @param maximum list size and therefore events signal to listen for
	 * @return the buffered collection
	 * @since 2.0
	 */
	public final Promise<List<O>> toList(long maximum) {
		if (maximum > 0)
			return take(maximum).buffer().next();
		else {
			return buffer().next();
		}
	}

	/**
	 * Blocking call to pass values from this stream to the queue that can be polled from a consumer.
	 *
	 * @return the buffered queue
	 * @since 2.0
	 */
	public final CompletableBlockingQueue<O> toBlockingQueue() throws InterruptedException {
		return toBlockingQueue(-1);
	}

	/**
	 * Blocking call to eagerly fetch values from this stream
	 *
	 * @param maximum queue getCapacity(), a full queue might block the stream producer.
	 * @return the buffered queue
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final CompletableBlockingQueue<O> toBlockingQueue(int maximum) throws InterruptedException {
		final CompletableBlockingQueue<O> blockingQueue;
		Stream<O> tail = this;
		if (maximum > 0) {
			blockingQueue = new CompletableBlockingQueue<O>(maximum);
			tail = take(maximum);
		} else {
			blockingQueue = new CompletableBlockingQueue<O>(1);
		}

		Consumer terminalConsumer = new Consumer<Object>() {
			@Override
			public void accept(Object o) {
				blockingQueue.complete();
			}
		};

		TerminalCallbackAction<O> callbackAction = new TerminalCallbackAction<O>(getDispatcher(), new Consumer<O>() {
			@Override
			public void accept(O o) {
				try {
					blockingQueue.put(o);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
		}, terminalConsumer, terminalConsumer);

		tail.subscribe(callbackAction);
		callbackAction.requestMore(callbackAction.getCapacity());

		return blockingQueue;
	}


	/**
	 * Prevent a {@link Stream} to be cancelled. Cancel propagation occurs when last subscriber is cancelled.
	 *
	 * @return a new {@literal Stream} that is never cancelled.
	 */
	public Stream<O> keepAlive() {
		return lift(new Function<Dispatcher, Action<? super O, ? extends O>>() {
			@Override
			public Action<? super O, ? extends O> apply(Dispatcher dispatcher) {
				return Action.<O>broadcast(dispatcher, getCapacity()).env(getEnvironment()).keepAlive();
			}
		});
	}

	/**
	 * Subscribe the {@link reactor.rx.action.CombineAction#input()} to this Stream. Combining action through {@link
	 * reactor.rx.action.Action#combine()} allows for easy distribution of a full flow.
	 *
	 * @param subscriber the combined actions to subscribe
	 * @since 2.0
	 */
	public final <A> void subscribe(final CombineAction<O, A> subscriber) {
		subscribe(subscriber.input());
	}

	@Override
	public Dispatcher getDispatcher() {
		return SynchronousDispatcher.INSTANCE;
	}

	@Override
	public long getCapacity() {
		return Long.MAX_VALUE;
	}

	public Timer getTimer(){
		return getEnvironment() == null ? Environment.timer() : getEnvironment().getTimer();
	}

	/**
	 * Get the assigned {@link reactor.core.Environment}.
	 *
	 * @return current {@link Environment}
	 */
	public Environment getEnvironment() {
		return null;
	}

	/**
	 * Get the current action child subscription
	 *
	 * @return current child {@link reactor.rx.subscription.PushSubscription}
	 */
	public PushSubscription<O> downstreamSubscription() {
		return null;
	}

	/**
	 * Try cleaning a given subscription from the stream references. Unicast implementation such as IterableStream
	 * (Streams.from(1,2,3)) or SupplierStream (Streams.generate(-> 1)) won't need to perform any job and as such will
	 * return @{code false} upon this call.
	 * Alternatively, Action and HotStream (Streams.from()) will clean any reference to that subscription from their
	 * internal registry and might return {@code true} if successful.
	 *
	 * @return current child {@link reactor.rx.subscription.PushSubscription}
	 */
	public boolean cleanSubscriptionReference(PushSubscription<O> oPushSubscription) {
		return false;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName();
	}

}