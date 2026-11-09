# Third-Party Notices

Palette Nav 上网导航（以下简称「本项目」）的源码、图标与随包静态资源说明如下。

## 随包资源

| 资源 | 位置 | 来源与授权 |
| --- | --- | --- |
| 图标雪碧图 | `src/main/resources/static/nav/vendor/palette-icons.js` | 本项目自绘的 24×24 线条图标，随本项目以 MIT 许可证分发。 |
| 默认导航数据图标 | `src/main/resources/data/nav-defaults.json` | 引用上述自制雪碧图，不含第三方图标图形。 |
| 背景图 | `src/main/resources/static/nav/img/background.jpg` | 本项目自制或已取得授权。 |
| 默认 LOGO / 默认图标 | `src/main/resources/static/nav/img/logo-default.png`、`default-icon.png` | 本项目自制或已取得授权。 |
| 插件 LOGO | `src/main/resources/logo.png` | 本项目自制或已取得授权。 |
| 一言文本 | `src/main/resources/data/quotes.txt` | 公开流传的励志语句汇编，仅作为页面装饰文本使用。 |

本项目**不再随包分发任何第三方图标集**。旧版本曾包含 iconfont.cn 生成的雪碧图，已在新版本中移除。

## 商标与品牌名称

默认导航数据中包含「腾讯视频」「GitHub」「哔哩哔哩」等第三方服务名称与网址，仅用于让用户点击跳转到对应服务，不表示本项目与这些服务的运营方存在合作、关联或背书关系。相关名称、商标与标识归各自权利人所有。

## 运行时依赖

本项目作为 Halo 插件运行，编译期依赖 Halo 提供的 API、Spring Framework、jsoup 等运行库；这些依赖由 Halo 自身分发，不随插件 JAR 打包。其许可证以 Halo 发行版中的声明为准。

## 联系

如权利人认为本仓库中的任何素材侵犯其权益，请通过 <https://github.com/litxiaoxi/halo-plugin-palette-nav/issues> 联系，我们会核实并移除相关内容。