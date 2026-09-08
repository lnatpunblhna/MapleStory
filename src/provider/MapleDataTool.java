package provider;

import java.awt.Point;
import java.awt.image.BufferedImage;
import provider.WzXML.MapleDataType;

public class MapleDataTool
{
    public static String getString(final MapleData data) {
        return (String)data.getData();
    }
    
    public static String getString(final MapleData data, final String def) {
        if (data == null || data.getData() == null) {
            return def;
        }
        return (String)data.getData();
    }
    
    public static String getString(final String path, final MapleData data) {
        return getString(data.getChildByPath(path));
    }
    
    public static String getString(final String path, final MapleData data, final String def) {
        return getString(data.getChildByPath(path), def);
    }
    
    // 下面几个数值取值统一走 Number：NX 只有 Int64 和 Double 两种数值类型，
    // WZ 的 short/int 都变成 Int64、float/double 都变成 Double，
    // 原先那种 (float)data.getData() 的精确装箱强转会直接 ClassCastException。
    public static double getDouble(final MapleData data) {
        return ((Number)data.getData()).doubleValue();
    }

    public static float getFloat(final MapleData data) {
        return ((Number)data.getData()).floatValue();
    }

    public static float getFloat(final MapleData data, final float def) {
        if (data == null || data.getData() == null) {
            return def;
        }
        return ((Number)data.getData()).floatValue();
    }

    public static int getInt(final MapleData data) {
        return ((Number)data.getData()).intValue();
    }

    public static int getInt(final MapleData data, final int def) {
        if (data == null || data.getData() == null) {
            return def;
        }
        if (data.getType() == MapleDataType.STRING) {
            return Integer.parseInt(getString(data));
        }
        return ((Number)data.getData()).intValue();
    }
    
    public static int getInt(final String path, final MapleData data) {
        return getInt(data.getChildByPath(path));
    }
    
    public static int getIntConvert(final MapleData data) {
        if (data.getType() == MapleDataType.STRING) {
            return Integer.parseInt(getString(data));
        }
        return getInt(data);
    }
    
    public static int getIntConvert(final String path, final MapleData data) {
        final MapleData d = data.getChildByPath(path);
        if (d.getType() == MapleDataType.STRING) {
            return Integer.parseInt(getString(d));
        }
        return getInt(d);
    }
    
    public static int getInt(final String path, final MapleData data, final int def) {
        return getInt(data.getChildByPath(path), def);
    }
    
    public static int getIntConvert(final String path, final MapleData data, final int def) {
        if (data == null) {
            return def;
        }
        final MapleData d = data.getChildByPath(path);
        if (d == null) {
            return def;
        }
        if (d.getType() == MapleDataType.STRING) {
            try {
                return Integer.parseInt(getString(d));
            }
            catch (NumberFormatException nfe) {
                return def;
            }
        }
        return getInt(d, def);
    }
    
    public static BufferedImage getImage(final MapleData data) {
        final Object canvas = data.getData();
        if (canvas == null) {
            // NX 在 --server 模式下不含 bitmap 数据。这个方法目前没有任何调用者，
            // 真要用图像就得改用 --client 模式生成并给 NXFile 加 LZ4 解码。
            throw new UnsupportedOperationException("NX 数据里没有图像: "
                    + getFullDataPath(data));
        }
        return ((MapleCanvas)canvas).getImage();
    }
    
    public static Point getPoint(final MapleData data) {
        return (Point)data.getData();
    }
    
    public static Point getPoint(final String path, final MapleData data) {
        return getPoint(data.getChildByPath(path));
    }
    
    public static Point getPoint(final String path, final MapleData data, final Point def) {
        final MapleData pointData = data.getChildByPath(path);
        if (pointData == null) {
            return def;
        }
        return getPoint(pointData);
    }
    
    public static String getFullDataPath(final MapleData data) {
        String path = "";
        for (MapleDataEntity myData = data; myData != null; myData = myData.getParent()) {
            path = myData.getName() + "/" + path;
        }
        return path.substring(0, path.length() - 1);
    }
}
