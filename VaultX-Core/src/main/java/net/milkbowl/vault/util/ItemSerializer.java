package net.milkbowl.vault.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public class ItemSerializer {

    private static final java.lang.invoke.MethodHandle SERIALIZE_BYTES_MH;
    private static final java.lang.invoke.MethodHandle DESERIALIZE_BYTES_MH;

    static {
        java.lang.invoke.MethodHandle sMh = null;
        java.lang.invoke.MethodHandle dMh = null;
        try {
            java.lang.reflect.Method sMethod = ItemStack.class.getMethod("serializeAsBytes");
            sMh = java.lang.invoke.MethodHandles.lookup().unreflect(sMethod);
            java.lang.reflect.Method dMethod = ItemStack.class.getMethod("deserializeBytes", byte[].class);
            dMh = java.lang.invoke.MethodHandles.lookup().unreflect(dMethod);
        } catch (Throwable ignored) {}
        SERIALIZE_BYTES_MH = sMh;
        DESERIALIZE_BYTES_MH = dMh;
    }

    public static String serializeItem(ItemStack item) {
        if (item == null) return "";

        if (SERIALIZE_BYTES_MH != null) {
            try {
                byte[] bytes = (byte[]) SERIALIZE_BYTES_MH.invokeExact(item);
                return Base64.getEncoder().encodeToString(bytes);
            } catch (Throwable ignored) {}
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeObject(item);
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    public static ItemStack deserializeItem(String data) {
        if (data == null || data.isEmpty()) return null;

        if (DESERIALIZE_BYTES_MH != null) {
            try {
                byte[] bytes = Base64.getDecoder().decode(data);
                return (ItemStack) DESERIALIZE_BYTES_MH.invokeExact(bytes);
            } catch (Throwable ignored) {}
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            return (ItemStack) dataInput.readObject();
        } catch (Exception e) {
            return null;
        }
    }
}
