package com.meme.vwallet.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AsyncConfigTest {

    @Autowired
    private AsyncConfigurer asyncConfigurer;

    @Autowired
    private ThreadPoolTaskExecutor taskExecutor;

    @Test
    @DisplayName("AsyncConfigurer provides a bounded ThreadPoolTaskExecutor with vwallet-async- prefix")
    void asyncConfigurer_providesBoundedThreadPoolTaskExecutor() {
        Executor configured = asyncConfigurer.getAsyncExecutor();
        assertThat(configured).isSameAs(taskExecutor);
        assertThat(taskExecutor.getCorePoolSize()).isEqualTo(4);
        assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(16);
        assertThat(taskExecutor.getQueueCapacity()).isEqualTo(500);
        assertThat(taskExecutor.getThreadNamePrefix()).isEqualTo("vwallet-async-");
    }
}
