# VoidTrialChambersPlugin

---

## 📜 插件概覽

VoidTrialChambersPlugin 在「虛空」中為每位玩家生成獨立的 Trial Chamber 世界，並自動刷怪、計時與追蹤擊殺數。  
適用於 PaperMC 伺服器 (1.21.4+)，支援 Java 21。

主要功能：
- 為每位玩家創建獨立的 trial_* 世界  
- 多種難度：簡單、普通、地獄、吞夢噬念 
- 自動刷怪波次，地獄/吞夢噬念 難度附加特殊波次與負面效果
- 冷卻計時與 BossBar 提示  
- 自動清理空閒世界，減少伺服器資源消耗  
- 指令：傳送、退出、隊伍邀請與排行榜

---

## 📜 命令列表
```
trialchambers [難度]   — 進入指定難度的試煉世界 (簡單/普通/地獄/吞夢噬念)
exittrial             — 退出試煉並回到主世界出生點
trailteam [invite]    — 試煉邀請系統
trialleaderboard [...]— 查詢試煉副本排行榜
```

#### /trialchambers
- **參數**：`簡單｜普通｜地獄｜吞夢噬念`  
- **冷卻**：2.5 分鐘（OP 可加 `skip` 跳過）  
- **範例**：  
  ```
  /trialchambers 普通
  ```

#### /exittrial
- 立即移除身上所有試煉效果  
- 傳送回 `world` 世界出生點  
- 清除該玩家的 trial_* 世界與刷怪任務

其餘指令請參照遊戲內 `/trailteam`、`/trialleaderboard`。

---
## 🛠 建置與安裝

```bash
# 1. Clone 專案
git clone https://github.com/verdo568/voidtrialchambersplugin.git
cd voidtrialchambersplugin

# 2. 編譯 (需要 Java 21)
./gradlew build

# 3. 部署
將產出的 JAR 複製至伺服器 plugins/ 目錄

4. 啟動伺服器
```

---

## 🤝 參與貢獻

1. Fork 本專案  
2. 修正程式與撰寫測試  
3. 發起 Pull Request 

---

## 📄 授權條款

本專案採 APGL-3.0 License，詳見 `LICENSE`。  