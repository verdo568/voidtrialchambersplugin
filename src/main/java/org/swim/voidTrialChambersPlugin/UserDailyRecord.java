package org.swim.voidTrialChambersPlugin;

import java.time.LocalDate;

public class UserDailyRecord {
    private LocalDate date;
    private int count;

    public UserDailyRecord() {
        // 無參數建構子給 YAML 反序列化用
    }

    public UserDailyRecord(LocalDate date, int count) {
        this.date = date;
        this.count = count;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}