# 农心 Agent · 界面与素材定调

本轮完全替换原有的纸张、印章、农技站式排版。采用近黑绿色导航、清爽白色工作区、浅叶绿色行动按钮；把可操作的输入框留在首屏底部，把位置天气收进侧栏。

- 主色 `#17221c`，底色 `#f7f9f8`，强调色 `#c8ec86`。
- 品牌名“农心 Agent”，界面文案“把农事，放在心上。”
- 导航为问农心 / 我的田块 / 农事任务 / 农技资料。
- 输入信息不足、连接失败、尚未录入，都明确展示状态，不用演示数据填满空间。
- 田块卡的色块和作物图标只是分类装饰，不暗示真实图像、健康程度或生长状态。
- AI 建议经用户确认后才写入任务；任务是否执行由用户记录。

## 原创生图素材

本轮通过本机配置的 Image API，使用 codex-image2 技能生成并检查两张素材。模型 `gpt-image-2`，质量 `auto`，无真人或第三方品牌参考图。

### 背景

文件：`frontend/public/brand/field-study.png`。请求尺寸 `1536x1024`。作为裁切的短横幅，文字由 HTML 渲染，附“品牌概念影像 · 非实测田块”标注。

最终提示词：

> Asset type: wide brand artwork for a contemporary agricultural web workspace called Nongxin. A sophisticated editorial aerial photograph of lush dark emerald agricultural fields with sweeping softly curved parallel crop rows and a small grove. Natural vegetation, soft directional late-afternoon light, slightly muted realistic colors. Composition: panoramic landscape, strong repeated crop row rhythm filling the right half, darker quiet left half for overlay text. Color palette charcoal green #17221c, dark emerald, subtle lime #c8ec86. This is conceptual brand imagery, not a factual farm. No text, logos, data overlays, icons, people, tractors, buildings, watermark, digital glow, fantasy or rustic paper. Exceptionally composed premium agricultural magazine photography.

### Logo

文件：`frontend/public/brand/nongxin-mark.png`。请求尺寸 `1024x1024`。幼苗、字母 N 形负空间和田垄组合；兼作应用图标。仅为本轮设计素材，未做商标可注册性保证。

最终提示词：

> Asset type: square app brand mark for Nongxin Agent, a redesigned contemporary agricultural web app. Original minimal abstract sprout and field-row monogram subtly suggesting an N and a caring heart. One elegant bold unified icon with clever negative space, recognizable at 32px. Flat crisp geometric vector-like aesthetic. Solid near-black green #17221c square canvas. Centered generous margins, mark covers 65 percent of width. Solid ivory #f7f9f8 and lime #c8ec86 mark. No words, literal letters, gradients, shadows, 3D, textures, watermarks, seal, stamp, rustic paper, ink flourishes. Premium understated timeless identity.

已检查构图与实际输出，没有数据标注或第三方水印。页面使用现有图标库表达功能，不把装饰图当作真实农情。
