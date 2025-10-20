package im.bigs.pg.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/**
 * HTTP 클라이언트 설정
 * RestClient는 Spring Boot 3.2+ 동기 HTTP 클라이언트
 */
@Configuration
class HttpClientConfig {

    @Bean
    fun restClient(@Value("\${testpay.base-url}") baseUrl: String):
            RestClient {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }
}
