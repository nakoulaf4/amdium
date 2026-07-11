package dev.amdium.util;

import com.mojang.blaze3d.vertex.VertexBuffer;
import java.lang.reflect.Field;

public class ReflectionUtil {
    private static Field vboIdField = null;

    public static int getVertexBufferId(VertexBuffer vbo) {
        if (vboIdField == null) {
            // Перебираем все поля класса, чтобы найти нужный int ID,
            // независимо от того, какие маппинги сейчас установлены.
            // Iterate over all fields of the class to find the desired int ID,
            // regardless of which mappings are currently in use.
            for (Field field : VertexBuffer.class.getDeclaredFields()) {
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    vboIdField = field;
                    break;
                }
            }
        }

        try {
            if (vboIdField != null) {
                return vboIdField.getInt(vbo);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }
}
