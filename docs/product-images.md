# 订单演示图片

四张图片由内置 imagegen 生成，2026-10-28 保存到项目静态资源目录。它们仅用于学习订单的界面演示，页面带“演示图”角标，不代表真实商品、品牌或商家图片。生成后仅复制原始 PNG，没有运行图片编辑或 CLI。

| 商品           | 项目文件                                           | 订单快照路径                        |
| -------------- | -------------------------------------------------- | ----------------------------------- |
| 黑色蓝牙耳机   | `frontend/public/products/demo/black-earbuds.png`  | `/products/demo/black-earbuds.png`  |
| 白色无线鼠标   | `frontend/public/products/demo/white-mouse.png`    | `/products/demo/white-mouse.png`    |
| 白色陶瓷马克杯 | `frontend/public/products/demo/white-mug.png`      | `/products/demo/white-mug.png`      |
| 棉质四件套     | `frontend/public/products/demo/cotton-bedding.png` | `/products/demo/cotton-bedding.png` |

下面是最终提示词集。每张独立生成，完整提示词由公共部分与对应的 `Subject:` 拼接。

公共部分：

```text
Use case: product-mockup. Asset type: a square product thumbnail for a Chinese ecommerce learning demo. Clean photorealistic studio catalog photograph on a pale warm white background, soft diffuse light, subtle grounded shadow, centered product occupying roughly 70% of frame with generous clean margins. Clear silhouette at small card sizes. No people, no brand logos, no text, no watermark. Single product category only, not a collage.
```

`black-earbuds.png`：

```text
Subject: A pair of compact matte-black true-wireless in-ear Bluetooth earbuds with their matching open charging case, elegant generic unbranded design, three-quarter front view. Black colour must be obvious.
```

`white-mouse.png`：

```text
Subject: One white wireless computer mouse with a softly curved ergonomic body and a grey scroll wheel, generic unbranded design, three-quarter overhead view. No keyboard or computer.
```

`white-mug.png`：

```text
Subject: One plain white ceramic coffee mug with a comfortable rounded handle, subtle glazed ceramic texture, three-quarter front view, empty mug, no saucer or props.
```

`cotton-bedding.png`：

```text
Subject: A neatly made minimalist double bed showcasing a white cotton four-piece bedding set: one duvet cover, one flat sheet and two pillowcases. Subtle natural cotton texture, clean white and pale ivory palette, three-quarter slightly elevated view, product-focused catalog image with no room decoration or text.
```
