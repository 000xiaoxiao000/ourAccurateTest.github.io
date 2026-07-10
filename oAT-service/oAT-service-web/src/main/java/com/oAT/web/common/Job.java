package com.oAT.web.common;

import org.apache.commons.io.output.StringBuilderWriter;
import org.apache.commons.io.output.WriterOutputStream;
import org.springframework.util.Assert;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Future;

public class Job<T> {

    private String id;
    public volatile JobState state;
    private Future<?> future;
    private JobLogger logger;
    private JobProgress progress;
    private Date begin;
    private T data;

    public Job(T data) {
        this.data = data;
        id = UUID.randomUUID().toString()
                .replaceAll("-", "");
        state = JobState.wait;

        logger = new JobLogger();
        progress = new JobProgress();
        begin = new Date();
    }

    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public Job() {
        this(null);
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getId() {
        return id;
    }

    public JobState getState() {
        return state;
    }

    public Future<?> getFuture() {
        return future;
    }

    public JobProgress getProgress() {
        return progress;
    }

    public String getLog() {
        return logger.getBufferLog();
    }

    public JobLogger getLogger() {
        return logger;
    }

    public Date getBegin() {
        return begin;
    }

    public void setProgress(JobProgress progress) {
        this.progress = progress;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("CompareJobWrapper{");
        sb.append("data=").append(data);
        sb.append('}');
        return sb.toString();
    }


    public enum JobState {
        wait, active, finish, terminate, error;
    }

    public static class JobLogger {
        PrintStream printStream;
        StringBuilderWriter loggerOut;

        public JobLogger() {
            loggerOut = new StringBuilderWriter();
            printStream = new PrintStream(new WriterOutputStream(loggerOut, java.nio.charset.StandardCharsets.UTF_8), true);
        }

        public JobLogger(PrintStream printStream) {
            this.printStream = printStream;
        }

        public void info(String message) {
            StringBuilder logger = new StringBuilder();
            logger.append(new SimpleDateFormat("HH:mm:ss").format(new Date()));
            logger.append(" ");
            logger.append(message);
            printStream.println(logger.toString());
        }

        public String getBufferLog() {
            Assert.notNull(loggerOut, "the logger must be buffer model");
            printStream.flush();
            return loggerOut.toString();
        }

        public void error(String message) {
            StringBuilder logger = new StringBuilder();
            logger.append(new SimpleDateFormat("HH:mm:ss").format(new Date()));
            logger.append(" ");
            logger.append(message);
            printStream.println(String.format("<em class='logger error'>%s</em>", logger));
        }

        public void error(Throwable throwable) {
            throwable.printStackTrace(printStream);
        }
    }

    public static class JobProgress {
        public int total;
        public int loaded;
        private int percent;
        // 当前阶段占比
        private int proportion;
        private String name;

        // 进入下一个阶段
        public void next(String name, int proportion) {
            // 切换阶段时，认为前一阶段已经完成，累加其权重
            percent += this.proportion;
            total = 0;
            loaded = 0;
            this.proportion = proportion;
            this.name = name;
        }

        public void finish(String name) {
            this.percent = 100;
            this.total = 0;
            this.loaded = 0;
            this.proportion = 0;
            this.name = name;
        }

        private int computeCurrent() {
            if (total <= 0 || loaded <= 0) {
                return 0;
            }
            if (loaded >= total) {
                return proportion;
            }
            return (int) ((long) loaded * proportion / total);
        }

        // 实际进度=已完成阶段进度+当前阶段进度
        public int getPercent() {
            int currentFinish = percent + computeCurrent();
            return Math.min(currentFinish, 100);
        }

        public void updateName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

}
