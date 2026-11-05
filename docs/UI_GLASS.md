# 液态玻璃界面（Glass UI）

Geostep 的界面主干是 Material 3，但表面材质换成了「仿 iOS 液态玻璃」的等效实现。这份文档说明设计意图、参数取值与扩展方式，方便二次开发时保持一致。

## 为什么不是真正的背景模糊

iOS 的 Liquid Glass 依赖对背后图层做实时高斯模糊（backdrop blur）。Compose 里做不到跨组件模糊：

- `Modifier.blur(...)` 模糊的是**组件自身**内容，不是它背后的东西；
- 真正能模糊背后内容的是 `View.setRenderEffect(RenderEffect.createBlurEffect(...))`（API 31+），只能作用在 View 层，对 Compose 内的其他 Composable 无效，也没法跟着地图平移实时刷新；
- `RenderNode` 方案需要把地图单独包成 `AndroidView` 并维护第二份渲染，复杂度和功耗都不划算（本项目还要求低端机流畅）。

因此这里用四层「视觉等效」：半透明表面底 + 上亮下暗白渐变 + 渐变描边 + 顶部 1dp 高光，再叠一层柔和阴影制造厚度。

## 实现（`app/src/main/kotlin/com/geostep/app/ui/Glass.kt`）

| 成员 | 用途 | 关键参数 |
| --- | --- | --- |
| `Modifier.liquidGlass(shape, strength, shadowElevation, veilAlpha)` | 圆角卡片 | `strength = 0.4`、`shadowElevation = 14`、`veilAlpha = 0.92` |
| `Modifier.liquidGlassChip(shape, strength, veilAlpha)` | 胶囊与按钮 | `strength = 0.36`、`veilAlpha = 0.85` |
| `GlassPanel(modifier, shape, strength, shadowElevation, content)` | 容器写法糖 | 同上 |
| `Color.luminanceIsDark()` | 判断深浅主题 | 0.2126/0.7152/0.0722 加权亮度 < 0.5 |

组成顺序（顺序有语义，别乱改）：

```
shadow(ambient/spot)   // 双层柔和投影，制造悬浮厚度
  → clip(shape)        // 先裁切，后面才不会被方角溢出
  → background(veil)   // 表面色打底，保证文字可读（这是可读性的关键）
  → background(tint)   // 白色渐变，制造玻璃反光
  → border(stroke)     // 四色线性渐变描边：左上亮、右下暗
  → drawWithContent {  // 顶部 1dp 高光，玻璃「厚度」的来源
        高光线 12%→88% 宽
    }
```

## 透明度取值为什么是这些数

- **0.92 / 0.85 是有意为之**：试过 0.76，地图路名会顶到文字上，中文小字号尤其糊；0.92 还能看到地图的一点点层次，但文字始终清晰。
- 深色主题下白色渐变降到 `0.16 → 0.06`（`strength` 前置系数），避免暗底上出现灰雾感。
- 底部面板容器用 `surface.copy(alpha = 0.94f)` + `scrimColor = 黑 22%` + `tonalElevation = 0`：面板像一块玻璃薄片浮在地图上，同时 `tonalElevation` 归零避免 Material 3 的叠色把玻璃压回实心。

## 使用位置

| 位置 | 用法 |
| --- | --- |
| 顶部搜索栏、目的地卡片 | `liquidGlass(RoundedCornerShape(22.dp / 20.dp), shadowElevation = 12f / 16f)` |
| 状态胶囊、地图源胶囊 | `liquidGlassChip(CircleShape)` |
| 右侧缩放/定位按钮 | `liquidGlassChip(RoundedCornerShape(14.dp))` + `tapFeedback(scaleDown = 0.90f)` |
| 底部四个入口 | `modifier.liquidGlassChip(RoundedCornerShape(13.dp))` |
| 面板与设置页的中性卡片 | `liquidGlassChip(RoundedCornerShape(N.dp), veilAlpha = 0.9f)` |
| 底部面板容器 | `ModalBottomSheet(containerColor = surface.copy(alpha = 0.94f), scrimColor = 黑 22%, tonalElevation = 0.dp)` |

**不要**给彩色强调块（品牌卡、模式图标、错误提示条）套玻璃——语义色一旦半透明，警示强度就没了。这类块用 `primaryContainer` / `errorContainer` 实色。

## 扩展新玻璃元素

```kotlin
Surface(
    modifier = Modifier.liquidGlass(shape = RoundedCornerShape(18.dp), shadowElevation = 10f),
    shape = RoundedCornerShape(18.dp),
    color = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.onSurface, // 透明底必须显式给文字色
) {
    // 内容
}
```

三条规则：

1. **`color = Color.Transparent` 时必须给 `contentColor`**，否则沿用 Surface 的默认浅色，深色主题下会看不清。
2. 需要点击反馈时用 `Modifier.tapFeedback(scaleDown = …) { … }`（`ui/Interaction.kt`），别直接 `clickable`，否则没有按压缩放。
3. 玻璃层不要嵌套超过两层——多层半透明叠加会让底部内容糊成一团，也增加过度绘制的功耗。

## 功耗

玻璃本身只画渐变、描边和若干条线，没有额外离屏缓冲，因此在低端机上开销可以忽略。真正影响功耗的是地图瓦片与模拟 tick，见 `ui/Perf.kt`（低端机/低功耗模式下的开屏时长、瓦片线程数、缓存预算）。
