package com.oAT.agent;

import java.io.Serializable;
import java.util.Objects;

class TopicKey implements Serializable {
    final String topic;
    final String projectId;

    TopicKey(String topic, String projectId) {
        this.topic = topic;
        this.projectId = projectId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TopicKey)) return false;
        TopicKey that = (TopicKey) o;
        return Objects.equals(topic, that.topic) && Objects.equals(projectId, that.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topic, projectId);
    }

    @Override
    public String toString() {
        return topic + "@" + projectId.substring(0, Math.min(8, projectId.length()));
    }
}
