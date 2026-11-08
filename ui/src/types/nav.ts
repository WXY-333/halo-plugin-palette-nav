/**
 * Thin aliases over the generated OpenAPI models.
 *
 * The resource and console DTO models are generated from the Java DTOs and `@GVK`
 * extensions by `./gradlew generateApiClient`; do not redefine their fields here.
 */
export type {
  Metadata,
  NavCategory,
  NavCategorySpec,
  NavLink,
  NavLinkSpec,
  NavQuickLink,
  NavQuickLinkSpec,
  NavReorderItem,
  NavReorderRequest,
  NavSearchEngine,
  NavSearchEngineSpec,
} from "@/api/generated/models";
