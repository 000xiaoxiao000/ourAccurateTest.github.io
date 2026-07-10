package com.oAT.agent;

import java.io.Serializable;

class KnowledgeEntry implements Serializable {
    final String pattern;
    final String normalizedQuestionPattern;
    volatile String recommendedAnswerTemplate;
    final String topic;
    volatile int confirmations;
    volatile long lastUpdated;

    KnowledgeEntry(String pattern, String normalizedQuestionPattern,
                   String recommendedAnswerTemplate, String topic,
                   int confirmations, long lastUpdated) {
        this.pattern = pattern;
        this.normalizedQuestionPattern = normalizedQuestionPattern;
        this.recommendedAnswerTemplate = recommendedAnswerTemplate;
        this.topic = topic;
        this.confirmations = confirmations;
        this.lastUpdated = lastUpdated;
    }
}
