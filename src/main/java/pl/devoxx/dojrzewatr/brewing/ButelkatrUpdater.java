package pl.devoxx.dojrzewatr.brewing;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.nurkiewicz.asyncretry.RetryExecutor;
import com.ofg.infrastructure.correlationid.CorrelationIdHolder;
import com.ofg.infrastructure.correlationid.CorrelationIdUpdater;
import com.ofg.infrastructure.hystrix.CorrelatedCommand;
import com.ofg.infrastructure.web.resttemplate.fluent.ServiceRestClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.Assert;
import pl.devoxx.dojrzewatr.brewing.model.Ingredients;
import pl.devoxx.dojrzewatr.brewing.model.Version;
import pl.devoxx.dojrzewatr.brewing.model.Wort;

import static com.netflix.hystrix.HystrixCommand.Setter.withGroupKey;
import static com.netflix.hystrix.HystrixCommandGroupKey.Factory.asKey;

@Slf4j
class ButelkatrUpdater {

    private final ServiceRestClient serviceRestClient;
    private final RetryExecutor retryExecutor;
    private final BrewProperties brewProperties;
    private final Meter brewMeter;

    public ButelkatrUpdater(ServiceRestClient serviceRestClient, RetryExecutor retryExecutor, BrewProperties brewProperties, MetricRegistry metricRegistry) {
        this.serviceRestClient = serviceRestClient;
        this.retryExecutor = retryExecutor;
        this.brewProperties = brewProperties;
        this.brewMeter = metricRegistry.meter("brew");
    }

    @Async
    public void updateButelkatrAboutBrewedBeer(final Ingredients ingredients, final String correlationId) {
        CorrelationIdUpdater.updateCorrelationId(correlationId);
        notifyPrezentatr();
        try {
            Long timeout = brewProperties.getTimeout();
            log.info("Brewing beer... it will take [{}] ms", timeout);
            Thread.sleep(timeout);
            brewMeter.mark();
        } catch (InterruptedException e) {
            log.error("Exception occurred while brewing beer", e);
        }
        notifyButelkatr(ingredients);
    }

    private void notifyPrezentatr() {
        serviceRestClient.forService("prezentatr").retryUsing(retryExecutor)
                .put().onUrl("/feed/dojrzewatr")
                .withoutBody()
                .withHeaders().contentType(Version.PREZENTATR_V1)
                .andExecuteFor().ignoringResponseAsync();
    }

    private void notifyButelkatr(Ingredients ingredients) {
        serviceRestClient.forService("butelkatr")
                .retryUsing(retryExecutor)
                .post()
                .withCircuitBreaker(withGroupKey(asKey("butelkatr_notification")))
                .onUrl("/bottle")
                .body(new Wort(getQuantity(ingredients)))
                .withHeaders().contentType(Version.BUTELKATR_V1)
                .andExecuteFor()
                .ignoringResponseAsync();
    }

    private Integer getQuantity(Ingredients ingredients) {
        Assert.notEmpty(ingredients.ingredients);
        return ingredients.ingredients.get(0).getQuantity();
    }

}
