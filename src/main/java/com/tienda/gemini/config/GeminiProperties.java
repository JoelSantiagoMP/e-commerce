package com.tienda.gemini.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "gemini.api")
public class GeminiProperties {

    private String key;
    private String model = "gemini-2.5-flash";
    private String url = "https://generativelanguage.googleapis.com/v1beta/models";
}
