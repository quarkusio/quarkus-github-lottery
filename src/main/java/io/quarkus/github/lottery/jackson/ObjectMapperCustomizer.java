package io.quarkus.github.lottery.jackson;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkiverse.githubapp.runtime.UtilsProducer;
import io.quarkus.runtime.Startup;

@Singleton
@Startup
public class ObjectMapperCustomizer {

    @Inject
    public void addModules(@UtilsProducer.Yaml ObjectMapper yamlObjectMapper) {
        yamlObjectMapper.registerModule(new Jdk8Module());
        yamlObjectMapper.registerModule(new JavaTimeModule());
    }

}
