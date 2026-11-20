package com.aaruchalet.quant.backtest;
import org.springframework.core.io.*;

final class SettingsResource {
    static Resource resolve(String location) {
        return location.startsWith("classpath:") ? new ClassPathResource(location.substring(10))
                : new FileSystemResource(location.startsWith("file:") ? java.nio.file.Path.of(java.net.URI.create(location))
                        : java.nio.file.Path.of(location));
    }
}
