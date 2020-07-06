package io.scalecube.services.auth;

import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Returns auth data by given credentials. Client code shall store returned result under {@link
 * Authenticator#AUTH_CONTEXT_KEY} key in {@link reactor.util.context.Context} to propagate auth
 * data to downstream components.
 *
 * @see PrincipalMapper
 * @param <R> auth data type
 */
@FunctionalInterface
public interface Authenticator<R> extends Function<Map<String, String>, Mono<R>> {

  String AUTH_CONTEXT_KEY = "auth.context";
}
