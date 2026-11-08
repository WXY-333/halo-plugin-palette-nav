import { definePlugin } from "@halo-dev/ui-shared";
import { markRaw } from "vue";
import RiNavigationLine from "~icons/ri/navigation-line";
import "./styles/console.css";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/palette-nav",
        name: "PaletteNav",
        component: () => import("./views/PaletteNavView.vue"),
        meta: {
          title: "Palette Nav 上网导航",
          permissions: ["plugin:palette-nav:manage"],
          searchable: true,
          menu: {
            name: "Palette Nav",
            group: "tool",
            icon: markRaw(RiNavigationLine),
            priority: 10,
          },
        },
      },
    },
  ],
  extensionPoints: {},
});
