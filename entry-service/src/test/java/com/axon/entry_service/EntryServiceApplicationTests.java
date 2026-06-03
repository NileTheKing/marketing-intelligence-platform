package com.axon.entry_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import com.axon.entry_service.service.redisListener.ReservationExpirationListener;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
class EntryServiceApplicationTests {

	@MockBean
	private StringRedisTemplate stringRedisTemplate;

	@MockBean
	private RedisTemplate<String, Object> redisTemplate;

	@MockBean
	private RedisConnectionFactory redisConnectionFactory;

	@MockBean
	private RedisMessageListenerContainer redisMessageListenerContainer;

	@MockBean
	private ReservationExpirationListener reservationExpirationListener;

	@MockBean
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Test
	void contextLoads() {
	}

}
