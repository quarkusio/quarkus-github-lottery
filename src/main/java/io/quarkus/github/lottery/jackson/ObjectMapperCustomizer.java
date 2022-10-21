package io.quarkus.github.lottery.jackson;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.quarkiverse.githubapp.runtime.UtilsProducer;
import io.quarkus.runtime.Startup;

@Singleton
@Startup
public class ObjectMapperCustomizer {

    @Inject
    public void addModules(ObjectMapper jsonMapper, @UtilsProducer.Yaml ObjectMapper yamlMapper) {
        for (ObjectMapper mapper : List.of(jsonMapper, yamlMapper)) {
            mapper.registerModule(new Jdk8Module());
            mapper.registerModule(new JavaTimeModule());
            mapper.registerModule(new ParameterNamesModule());
        }
    }

}
