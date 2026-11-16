package edu.jlu.facegeometry;

import java.util.Arrays;
import java.util.List;

final class Geometry {
    static final int LANDMARK_COUNT = 468;

    enum Profile {
        GENERAL("通用"), MASCULINE("男性模板"), FEMININE("女性模板");

        final String title;

        Profile(String title) {
            this.title = title;
        }
    }

    record Point(double x, double y) {
        Point minus(Point other) {
            return new Point(x - other.x, y - other.y);
        }

        double dot(Point other) {
            return x * other.x + y * other.y;
        }

        double distance(Point other) {
            return Math.hypot(x - other.x, y - other.y);
        }
    }

    record Metric(String name, String value, double score, int weight) {}

    record Features(
            double symmetryError, double upperThird, double midThird, double lowerThird,
            double eyeUnits, double browGap, double noseLength, double lipAspect,
            double jawWidth, double eyeAspect, double faceAspect) {

        List<Metric> metrics(Profile profile) {
            int jawWeight = switch (profile) {
                case GENERAL -> 8;
                case MASCULINE -> 18;
                case FEMININE -> 5;
            };
            int browWeight = switch (profile) {
                case GENERAL -> 9;
                case MASCULINE -> 13;
                case FEMININE -> 5;
            };
            int lipWeight = profile == Profile.FEMININE ? 14 : 6;
            int eyeWeight = profile == Profile.FEMININE ? 12 : 5;
            int faceWeight = profile == Profile.FEMININE ? 10 : 5;
            double thirdError = (Math.abs(upperThird - 1.0 / 3)
                    + Math.abs(midThird - 1.0 / 3) + Math.abs(lowerThird - 1.0 / 3)) / 3;
            return Arrays.asList(
                    new Metric("左右对称", percent(symmetryError) + " 镜像偏差",
                            fit(symmetryError, 0, .13), 24),
                    new Metric("三庭（近似）", percent(upperThird) + " / " + percent(midThird)
                            + " / " + percent(lowerThird), fit(thirdError, 0, .15), 16),
                    new Metric("五眼比例", format(eyeUnits) + " 眼宽",
                            fit(eyeUnits, 5, 2.3), 12),
                    new Metric("眉眼间距", percent(browGap) + " 眼宽",
                            fit(browGap, .33, .40), browWeight),
                    new Metric("鼻梁区比例", percent(noseLength) + " 脸长",
                            fit(noseLength, .26, .17), 8),
                    new Metric("唇形比例", percent(lipAspect) + "（厚 / 宽）",
                            fit(lipAspect, .29, .32), lipWeight),
                    new Metric("下颌轮廓", percent(jawWidth) + "（下颌 / 脸宽）",
                            fit(jawWidth, .76, .32), jawWeight),
                    new Metric("眼型开合", percent(eyeAspect) + "（高 / 宽）",
                            fit(eyeAspect, .26, .27), eyeWeight),
                    new Metric("面部外形", format(faceAspect) + "（脸长 / 脸宽）",
                            fit(faceAspect, 1.30, .55), faceWeight));
        }

        double score(Profile profile) {
            List<Metric> metrics = metrics(profile);
            double weightedSum = 0;
            int totalWeight = 0;
            for (Metric metric : metrics) {
                weightedSum += metric.score() * metric.weight();
                totalWeight += metric.weight();
            }
            return 1 + 9 * weightedSum / totalWeight;
        }
    }

    private static final int[][] MIRROR_PAIRS = {
            {33, 263}, {133, 362}, {159, 386}, {145, 374},
            {105, 334}, {98, 327}, {61, 291}, {234, 454}, {172, 397}
    };

    private Geometry() {}

    static Features measure(Point[] mesh) {
        if (mesh.length != LANDMARK_COUNT) {
            throw new IllegalArgumentException("关键点数量不足");
        }
        for (Point point : mesh) {
            if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())) {
                throw new IllegalArgumentException("关键点数据不完整");
            }
        }

        // Align the X axis to the outer eye corners before measuring projected lengths.
        Point eyeAxis = mesh[263].minus(mesh[33]);
        double eyeSpan = eyeAxis.distance(new Point(0, 0));
        if (eyeSpan < 12) {
            throw new IllegalArgumentException("请靠近镜头，或选择脸部更清晰的照片");
        }
        Point horizontal = new Point(eyeAxis.x() / eyeSpan, eyeAxis.y() / eyeSpan);
        Point vertical = new Point(-horizontal.y(), horizontal.x());
        Point origin = mesh[168];
        Point[] aligned = new Point[mesh.length];
        for (int i = 0; i < mesh.length; i++) {
            Point delta = mesh[i].minus(origin);
            aligned[i] = new Point(delta.dot(horizontal), delta.dot(vertical));
        }

        double width = xLength(aligned, 234, 454);
        double height = aligned[152].y() - aligned[10].y();
        double eyeWidth = average(xLength(aligned, 33, 133), xLength(aligned, 362, 263));
        double lipWidth = xLength(aligned, 61, 291);
        if (width < 32 || height < 32 || eyeWidth < 4 || lipWidth < 4
                || aligned[168].y() <= aligned[10].y()
                || aligned[2].y() <= aligned[168].y()
                || aligned[152].y() <= aligned[2].y()) {
            throw new IllegalArgumentException("请正对镜头，确保额头、眼睛和下巴完整可见");
        }

        double asymmetry = 0;
        for (int[] pair : MIRROR_PAIRS) {
            Point left = aligned[pair[0]];
            Point right = aligned[pair[1]];
            asymmetry += left.distance(new Point(-right.x(), right.y()));
        }
        double upper = yLength(aligned, 10, 168) / height;
        double middle = yLength(aligned, 168, 2) / height;
        double lower = yLength(aligned, 2, 152) / height;
        return new Features(asymmetry / (MIRROR_PAIRS.length * width),
                upper, middle, lower, width / eyeWidth,
                average(yLength(aligned, 105, 159), yLength(aligned, 334, 386)) / eyeWidth,
                yLength(aligned, 168, 1) / height, yLength(aligned, 0, 17) / lipWidth,
                xLength(aligned, 172, 397) / width,
                average(yLength(aligned, 159, 145), yLength(aligned, 386, 374)) / eyeWidth,
                height / width);
    }

    private static double xLength(Point[] points, int first, int second) {
        return Math.abs(points[first].x() - points[second].x());
    }

    private static double yLength(Point[] points, int first, int second) {
        return Math.abs(points[first].y() - points[second].y());
    }

    private static double average(double a, double b) {
        return (a + b) / 2;
    }

    private static double fit(double actual, double target, double tolerance) {
        return Math.max(0, 1 - Math.abs(actual - target) / tolerance);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.CHINA, "%.2f", value);
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.CHINA, "%.0f%%", 100 * value);
    }
}
