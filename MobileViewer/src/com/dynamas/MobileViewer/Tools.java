package com.dynamas.MobileViewer;

public class Tools {
    public static int ToInt(byte[] array, int index) {
        int result;

        if (array[index] >= 0) result = array[index];
        else result = 256 + array[index];
        if (array[index + 1] >= 0) result += array[index + 1] * 256;
        else result += (256 + array[index + 1]) * 256;
        if (array[index + 2] >= 0) result += array[index + 2] * 256 * 256;
        else result += (256 + array[index + 2]) * 256 * 256;
        if (array[index + 3] >= 0) result += array[index + 3] * 256 * 256 * 256;
        else result += (256 + array[index + 3]) * 256 * 256 * 256;
        return (result);
    }

    public static void ToArray(byte[] array, int index, int val) {
        if (val % 256 > 127) array[index] = (byte)(val % 256);
        else array[index] = (byte)(val % 256 - 256);
        val /= 256;
        if (val % 256 > 127) array[index + 1] = (byte)(val % 256);
        else array[index + 1] = (byte)(val % 256 - 256);
        val /= 256;
        if (val % 256 > 127) array[index + 2] = (byte)(val % 256);
        else array[index + 2] = (byte)(val % 256 - 256);
        val /= 256;
        if (val % 256 > 127) array[index + 3] = (byte) val;
        else array[index + 3] = (byte)(val - 256);
    }

    public static int Strlen(byte[] array) {
        int ii;

        for (ii = 0; ii < array.length; ii++)
        if (array[ii] == 0) break;
        return (ii);
    }

    public static int Strlen(byte[] array, int start) {
        int count;

        for (count = 0; start < array.length; start++, count++)
        if (array[start] == 0) break;
        return (count);
    }


    public static boolean NeedDoubleHeight(int width, int height) {
        switch (width) {
            //      case 320:
            //        if( height==120 )    return( true );
            //        break;
            case 640:
                if (height == 240 || height == 288) return (true);
                break;
            case 704:
                if (height == 240 || height == 288) return (true);
                break;
            case 720:
                if (height == 240 || height == 288) return (true);
                break;
            case 768:
                if (height == 288) return (true);
                break;
        }
        return (false);
    }


    public static void FitBlock(int width, int height,
    double scale_w, double scale_h, int border_width, int border_height,
    GBlock rv_block) {
        // 先去掉外框空間
        width -= border_width;
        height -= border_height;

        if (width * (scale_h / scale_w) > height) // Fit height (base on height)
        {
            rv_block.Width = (int)(height * (scale_w / scale_h) + border_width);
            rv_block.Height = height + border_height;
            rv_block.X = (width + border_width - rv_block.Width) / 2;
            rv_block.Y = 0;
        } else // Fit width (base on width)
        {
            rv_block.Width = width + border_width;
            rv_block.Height = (int)(width * (scale_h / scale_w) + border_height);
            rv_block.X = 0;
            rv_block.Y = (height + border_height - rv_block.Height) / 2;
        }
    }
}
