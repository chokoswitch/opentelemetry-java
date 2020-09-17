/*
 * Copyright The OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.opentelemetry.context;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * A context propagation mechanism which can carry scoped-values across API boundaries and between
 * threads.
 *
 * <p>A Context object can be {@link #attach attached} to the {@link ContextStorage}, which
 * effectively forms a <b>scope</b> for the context. The scope is bound to the current thread.
 * Within a scope, its Context is accessible even across API boundaries, through {@link #current}.
 * The scope is later exited by {@link Scope#close()} closing} the scope.
 *
 * <p>Context objects are immutable and inherit state from their parent. To add or overwrite the
 * current state a new context object must be created and then attached, replacing the previously
 * bound context. For example:
 *
 * <pre>
 *   Context withCredential = Context.current().withValue(CRED_KEY, cred);
 *   withCredential.wrap(new Runnable() {
 *     public void run() {
 *        readUserRecords(userId, CRED_KEY.get());
 *     }
 *   }).run();
 * </pre>
 *
 *
 *
 * <p>Notes and cautions on use:
 *
 * <ul>
 *   <li>Every {@code attach()} must be followed by a {@code Scope#close()}. Breaking these rules
 *       may lead to memory leaks.
 *   <li>While Context objects are immutable they do not place such a restriction on the state they
 *       store.
 *   <li>Context is not intended for passing optional parameters to an API and developers should
 *       take care to avoid excessive dependence on context when designing an API.
 * </ul>
 */
public final class Context {

  /** Returns the root {@link Context} which all other {@link Context} are derived from. */
  public static Context root() {
    return storage().rootContext();
  }

  /** Return the context associated with the current scope. */
  public static Context current() {
    return storage().current();
  }

  /**
   * Returns the default {@link ContextStorage} used to attach {@link Context}s to scopes of
   * execution. Should only be used when defining your own {@link ContextStorage} in case you want
   * to delegate functionality to the default implementation.
   */
  public static ContextStorage threadLocalStorage() {
    return ThreadLocalContextStorage.INSTANCE;
  }

  static ContextStorage storage() {
    return LazyStorage.storage;
  }

  private final PersistentHashArrayMappedTrie.Node<ContextKey<?>, Object> entries;

  private Context(PersistentHashArrayMappedTrie.Node<ContextKey<?>, Object> entries) {
    this.entries = entries;
  }

  Context() {
    entries = null;
  }

  /**
   * Returns the value stored in this {@link Context} for the given {@link ContextKey}, or {@code
   * null} if there is no value for the key in this context.
   */
  @Nullable
  public <V> V getValue(ContextKey<V> key) {
    // Because withValue enforces the value for a key is its type, this is always safe.
    @SuppressWarnings("unchecked")
    V value = (V) PersistentHashArrayMappedTrie.get(entries, key);
    return value;
  }

  /**
   * Returns a new context with the given key value set.
   *
   * <pre>{@code
   * Context withCredential = Context.current().withValue(CRED_KEY, cred);
   * withCredential.run(new Runnable() {
   *   public void run() {
   *      readUserRecords(userId, CRED_KEY.get());
   *   }
   * });
   * }</pre>
   *
   * <p>Note that multiple calls to {@link #withValue(ContextKey, Object)} can be chained together.
   * That is,
   *
   * <pre>
   * context.withValues(K1, V1, K2, V2);
   * // is the same as
   * context.withValue(K1, V1).withValue(K2, V2);
   * </pre>
   *
   * <p>Nonetheless, {@link Context} should not be treated like a general purpose map with a large
   * number of keys and values — combine multiple related items together into a single key instead
   * of separating them. But if the items are unrelated, have separate keys for them.
   */
  public <V> Context withValue(ContextKey<V> k1, V v1) {
    PersistentHashArrayMappedTrie.Node<ContextKey<?>, Object> newEntries =
        PersistentHashArrayMappedTrie.put(entries, k1, v1);
    return new Context(newEntries);
  }

  /** Returns a new context with the given key value set. */
  public <V1, V2> Context withValues(ContextKey<V1> k1, V1 v1, ContextKey<V2> k2, V2 v2) {
    PersistentHashArrayMappedTrie.Node<ContextKey<?>, Object> newEntries =
        PersistentHashArrayMappedTrie.put(entries, k1, v1);
    newEntries = PersistentHashArrayMappedTrie.put(newEntries, k2, v2);
    return new Context(newEntries);
  }

  /** Returns a new context with the given key value set. */
  public <V1, V2, V3> Context withValues(
      ContextKey<V1> k1, V1 v1, ContextKey<V2> k2, V2 v2, ContextKey<V3> k3, V3 v3) {
    PersistentHashArrayMappedTrie.Node<ContextKey<?>, Object> newEntries =
        PersistentHashArrayMappedTrie.put(entries, k1, v1);
    newEntries = PersistentHashArrayMappedTrie.put(newEntries, k2, v2);
    newEntries = PersistentHashArrayMappedTrie.put(newEntries, k3, v3);
    return new Context(newEntries);
  }

  /**
   * Create a new context with the given key value set.
   *
   * <p>For more than 4 key-value pairs, note that multiple calls to {@link #withValue} can be
   * chained together. That is,
   *
   * <pre>
   * context.withValues(K1, V1, K2, V2);
   * // is the same as
   * context.withValue(K1, V1).withValue(K2, V2);
   * </pre>
   *
   * <p>Nonetheless, {@link Context} should not be treated like a general purpose map with a large
   * number of keys and values — combine multiple related items together into a single key instead
   * of separating them. But if the items are unrelated, have separate keys for them.
   */
  public <V1, V2, V3, V4> Context withValues(
      ContextKey<V1> k1,
      V1 v1,
      ContextKey<V2> k2,
      V2 v2,
      ContextKey<V3> k3,
      V3 v3,
      ContextKey<V4> k4,
      V4 v4) {
    PersistentHashArrayMappedTrie.Node<ContextKey<?>, Object> newEntries =
        PersistentHashArrayMappedTrie.put(entries, k1, v1);
    newEntries = PersistentHashArrayMappedTrie.put(newEntries, k2, v2);
    newEntries = PersistentHashArrayMappedTrie.put(newEntries, k3, v3);
    newEntries = PersistentHashArrayMappedTrie.put(newEntries, k4, v4);
    return new Context(newEntries);
  }

  /**
   * Attaches this {@link Context}, making it the current {@link Context} and returns a {@link
   * Scope} which corresponds to the scope of execution this context is attached for. {@link
   * Context#current()} will return this {@link Context} until {@link Scope#close()} is called.
   * {@link Scope#close()} must be called to properly restore the previous context from before this
   * scope of execution or context will not work correctly. It is recommended to use
   * try-with-resources to call {@link Scope#close()} automatically.
   *
   * <pre>{@code
   * Context prevCtx = Context.current();
   * try (Scope ignored = ctx.attach()) {
   *   assert Context.current() == ctx;
   *   ...
   * }
   * assert Context.current() == prevCtx;
   * }</pre>
   */
  public Scope attach() {
    final Context thisCtx = this;
    final Context prevCtx = storage().attach(this);

    if (thisCtx == prevCtx) {
      // Already attached, so just creating a new scope that doesn't do anything.
      return NoopScope.INSTANCE;
    }

    return new Scope() {
      @Override
      public void close() {
        storage().detach(thisCtx, prevCtx);
      }
    };
  }

  /**
   * Returns a {@link Runnable} that attaches this {@link Context} and then invokes the input {@link
   * Runnable}.
   */
  public Runnable wrap(final Runnable runnable) {
    return new Runnable() {
      @Override
      public void run() {
        try (Scope ignored = attach()) {
          runnable.run();
        }
      }
    };
  }

  /**
   * Returns a {@link Runnable} that attaches this {@link Context} and then invokes the input {@link
   * Runnable}.
   */
  public <T> Callable<T> wrap(final Callable<T> callable) {
    return new Callable<T>() {
      @Override
      public T call() throws Exception {
        try (Scope ignored = attach()) {
          return callable.call();
        }
      }
    };
  }

  /**
   * Returns an {@link Executor} that will execute callbacks in the given {@code executor},
   * attaching this {@link Context} before each execution.
   */
  public Executor wrap(final Executor executor) {
    return new Executor() {
      @Override
      public void execute(Runnable command) {
        executor.execute(wrap(command));
      }
    };
  }

  /**
   * Returns an {@link ExecutorService} that will execute callbacks in the given {@code executor},
   * attaching this {@link Context} before each execution.
   */
  public ExecutorService wrap(ExecutorService executor) {
    return new ContextExecutorService(this, executor);
  }

  /**
   * Returns an {@link ScheduledExecutorService} that will execute callbacks in the given {@code
   * executor}, attaching this {@link Context} before each execution.
   */
  public ScheduledExecutorService wrap(ScheduledExecutorService executor) {
    return new ContextScheduledExecutorService(this, executor);
  }

  private enum NoopScope implements Scope {
    INSTANCE;

    @Override
    public void close() {}
  }
}
