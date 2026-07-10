package com.oAT.agent;

import com.oAT.ai.agent.FeedbackPersistenceService;

import java.io.Serializable;

class TopicStats implements Serializable {
    int totalFeedbacks = 0;
    int positiveCount = 0;
    int negativeCount = 0;

    synchronized void update(FeedbackPersistenceService.FeedbackRecord record) {
        totalFeedbacks++;
        if ("helpful".equals(record.getFeedbackType())
                || (record.getRating() != null && record.getRating() >= 4)) {
            positiveCount++;
        } else if (record.getRating() != null && record.getRating() <= 2) {
            negativeCount++;
        }
    }

    double satisfactionRate() {
        return totalFeedbacks > 0 ? (double) positiveCount / totalFeedbacks : 0;
    }
}
