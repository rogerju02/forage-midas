package com.jpmc.midascore.foundation;

import com.jpmc.midascore.repository.UserRepository;
import com.jpmc.midascore.repository.TransactionRecordRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Optional;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.entity.TransactionRecord;
import org.springframework.web.client.RestTemplate;

@Component
public class KafkaMessageQueue {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRecordRepository transactionRecordRepository;

    private static final Logger logger = LoggerFactory.getLogger(KafkaMessageQueue.class);

    // Store received transactions
    private final List<Transaction> receivedTransactions = new ArrayList<>();

    // Jackson object mapper for JSON deserialization
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Kafka listener that receives messages and deserializes them to Transaction objects
     */
    @KafkaListener(topics = "${general.kafka-topic}")
    public void listen(ConsumerRecord<String, String> record) {
        String message = record.value();
        logger.info("Received message: {}", message);

        try {
            // Deserialize incoming Kafka message into Transaction POJO
            Transaction transaction = objectMapper.readValue(message, Transaction.class);

            long senderId = transaction.getSenderId();
            long recipientId = transaction.getRecipientId();
            float amount = transaction.getAmount();

            // Look up sender and recipient
            Optional<UserRecord> senderOpt = userRepository.findById(senderId);
            Optional<UserRecord> recipientOpt = userRepository.findById(recipientId);

            if (senderOpt.isEmpty() || recipientOpt.isEmpty()) {
                logger.warn("Transaction rejected: invalid sender or recipient. Sender: {}, Recipient: {}", senderId, recipientId);
                return;
            }

            UserRecord sender = senderOpt.get();
            UserRecord recipient = recipientOpt.get();

            if (sender.getBalance() < amount) {
                logger.warn("Transaction rejected: insufficient funds. Sender: {}, Balance: {}, Amount: {}",
                        senderId, sender.getBalance(), amount);
                return;
            }

            Incentive incentive  = restTemplate.postForObject(
                    "http://localhost:8080/incentive",
                    transaction,
                    Incentive.class
            );
            float incentiveAmount;
            if (incentive != null) {
                incentiveAmount = incentive.getAmount();
            } else {
                incentiveAmount = 0.0f;
            }

            // Valid transaction: update balances
            sender.setBalance(sender.getBalance() - amount);
            recipient.setBalance(recipient.getBalance() + amount);

            userRepository.save(sender);
            userRepository.save(recipient);

            // Record transaction
            TransactionRecord txRecord = new TransactionRecord(sender, recipient, amount);
            transactionRecordRepository.save(txRecord);

            logger.info("Transaction processed successfully: {}", transaction);

        } catch (Exception e) {
            logger.error("Failed to process message: {}", message, e);
        }
    }
}