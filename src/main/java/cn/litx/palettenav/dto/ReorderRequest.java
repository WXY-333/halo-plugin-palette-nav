package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
@Schema(name = "NavReorderRequest", description = "Batch reorder request.")
public class ReorderRequest {

    @Schema(description = "Model Kind, e.g. NavLink", example = "NavLink")
    private String kind;

    @Schema(description = "Entities to renumber")
    private List<Item> items;

    @Data
    @Schema(name = "NavReorderItem", description = "A single sort update.")
    public static class Item {

        @Schema(description = "metadata.name of the entity")
        private String name;

        @Schema(description = "New sort value")
        private Integer sort;
    }
}
