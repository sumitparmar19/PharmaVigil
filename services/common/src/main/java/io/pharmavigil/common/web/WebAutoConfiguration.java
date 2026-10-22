// Registers the shared problem-details exception handler in every service that uses the common library.
package io.pharmavigil.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import(ApiExceptionHandler.class)
public class WebAutoConfiguration {
}
