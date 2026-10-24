package com.ewan.wallpaperbridge;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class BluetoothWireTest {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BluetoothWire.write(output, "收藏链接与文字");
        String text = BluetoothWire.read(new ByteArrayInputStream(output.toByteArray()));
        if (!text.equals("收藏链接与文字")) throw new AssertionError("UTF-8 framing");
        String proof = BluetoothWire.proof("key", "phone:1:收藏");
        if (!proof.equals("ecfb35e9c371b1c9d129799e5ef9f1c4cc8ee96a21b5d448e657e141b9c138cc"))
            throw new AssertionError("Python / Java proof mismatch: " + proof);
        if (BluetoothWire.matches(proof, "0".repeat(64))) throw new AssertionError("Wrong pairing accepted");
        try { BluetoothWire.read(new ByteArrayInputStream(new byte[]{127, -1, -1, -1}));
            throw new AssertionError("Unbounded frame"); } catch (IOException expected) { }
        try { BluetoothWire.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 3, 123}));
            throw new AssertionError("Incomplete frame"); } catch (IOException expected) { }
        System.out.println("Bluetooth frame / proof checks passed");
    }
}
