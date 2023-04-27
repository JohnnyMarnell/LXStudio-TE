package titanicsend.pattern.jon;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.*;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.color.LinkedColorParameter;
import titanicsend.model.TEEdgeModel;
import titanicsend.pattern.TEAudioPattern;
import titanicsend.pattern.TEPerformancePattern;
import titanicsend.util.TEMath;

import static titanicsend.util.TEMath.clamp;

// All LED platforms, no matter how large, must have KITT!
@LXCategory("Edge FG")
public class EdgeKITT extends TEPerformancePattern {
    double tailPct = 0.5f;

    public EdgeKITT(LX lx) {
        super(lx);

        controls.setRange(TEControlTag.SIZE, 0.5, 0.01, 1);

        addCommonControls();
    }

    double square(double n, double dutyCycle) {
        return (Math.abs(TEMath.fract(n)) <= dutyCycle) ? 1.0 : 0.0;
    }

    double triangle(double n) {
        return 2f * (0.5 - Math.abs(TEMath.fract(n) - 0.5));
    }

    public void runTEAudioPattern(double deltaMs) {

        // pick up the current color
        int baseColor = calcColor();

        // generate 0..1 ramp (sawtooth) from speed timer
        double t1 =  (-getTimeMs() % 4000) / 4000;
        tailPct = getSize();

// From a discussion of frame buffer-less, multidimensional KITT patterns
// on the Pixelblaze forum.
// https://forum.electromage.com/t/kitt-without-arrays/1219
        for (TEEdgeModel edge : model.getAllEdges()) {
            for (TEEdgeModel.Point point : edge.points) {

                double x = 0.5f * point.frac;
                double pct1 = x - t1;
                double pct2 = -x - t1;

                double w1 = Math.max(0f, (tailPct - 1 + triangle(pct1) * square(pct1, .5)) / tailPct);
                double w2 = Math.max(0f, (tailPct - 1 + triangle(pct2) * square(pct2, .5)) / tailPct);
                double bri = clamp((w1 * w1) + (w2 * w2),0,1);  // gamma correct both waves before combining
                baseColor = getGradientColor((float) (1.0-bri));
                bri = bri * 255;  // scale for output

                // clear and reset alpha channel
                baseColor = baseColor & ~LXColor.ALPHA_MASK;
                baseColor = baseColor | (((int) bri) << LXColor.ALPHA_SHIFT);
                colors[point.index] = baseColor;
            }
        }
    }
}