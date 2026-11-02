# 开发

[← 返回文档目录](../README.md#-文档)

## 测试与约定

```bash
python3 -m unittest
```

- **零运行时依赖**：只用标准库，测试用 `unittest`，不引入 pytest / requests。
- **数据库迁移**：只在 `store.py` 的 `MIGRATIONS` 末尾追加，不修改已有条目。
- **时间**：统一存 UTC ISO 字符串（`util.to_iso`）。
- 更多容易踩坑的约束（网络代理、记忆闭环、测试里要 mock 的联网调用等）见 [`CLAUDE.md`](../CLAUDE.md)。

## 新增一种来源

1. 在 `catchup/sources/` 下新建模块，继承 `Source`（feed 类继承 `FeedSource`，只需实现 `feeds()`）：

   ```python
   @register
   class Bluesky(Source):
       type = "bluesky"
       default_kind = "社区"
       label_format = "Bluesky · {channel}"

       def fetch(self):
           for handle in self.options["accounts"]:
               ...
               yield self.signal(url, title, summary, channel=handle, published=parse_time(ts), heat=likes / 100)
   ```

2. 在 `catchup/sources/__init__.py` 里导入这个模块。
3. 在 `config.json` 的 `sources` 里加一个 `{"type": "bluesky", ...}` 实例。
4. 在 `tests/` 里用 `unittest.mock` 替换 `catchup.http.get` / `get_json` 写解析测试。

### 约定

- URL 是去重主键，要尽量规范化（如 arXiv 去掉版本号、HN 优先用外链）。
- `heat` 由来源自己归一化，约 1 表示明显热门。
- 子请求失败用 `log.warning` 记录后继续。
- 榜单类来源（GitHub Trending、HF 模型热榜）设 `board = True`，`fetch()` 按名次返回：信号表按 URL 只记第一次上榜，每个榜当前的名次和这一轮上榜的开始时间另记在 `board_items` 表（`Store.update_board` / `Store.board`）。
