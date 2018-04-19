package io.scalecube.services;

import io.scalecube.services.api.ServiceMessage;
import io.scalecube.services.transport.server.api.ServerMessageAcceptor;

import java.util.Map;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class DefaultServicesMessageAcceptor implements ServerMessageAcceptor {

  private Map<String, LocalServiceMethodInvoke> endpoints;

  public DefaultServicesMessageAcceptor(Map<String, LocalServiceMethodInvoke> services) {
    this.endpoints = services;
  }

  @Override
  public Flux<ServiceMessage> requestChannel(Flux<ServiceMessage> request) {
    
    Mono<ServiceMessage> mono = Mono.from(request);
    Mono<LocalServiceMethodInvoke> handler = mono.map(message -> endpoints.get(message.qualifier()));
    return Flux.from(handler.map(mapper -> mapper.requestChannel(request)));
  }


  @Override
  public Flux<ServiceMessage> requestStream(ServiceMessage request) {
    return endpoints.get(request.qualifier()).requestStream(request);
  }

  @Override
  public Mono<ServiceMessage> requestResponse(ServiceMessage request) {
    return endpoints.get(request.qualifier()).requestResponse(request);
  }

  @Override
  public Mono<Void> fireAndForget(ServiceMessage request) {
    return endpoints.get(request.qualifier()).fireAndForget(request);
  }

}
