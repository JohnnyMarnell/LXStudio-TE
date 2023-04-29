package titanicsend.pattern.jon;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import titanicsend.model.TEEdgeModel;
import titanicsend.pattern.TEPerformancePattern;
import titanicsend.util.TEColor;
import titanicsend.util.TEMath;

import static titanicsend.util.TEMath.clamp;

// All LED platforms, no matter how large, must have KITT!
@LXCategory("Edge FG")
public class EdgeKITT extends TEPerformancePattern {

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

        // generate 0..1 ramp (sawtooth) from speed timer
        double t1 =  (-getTimeMs() % 4000) / 4000;

        // Size control sets the KITT tail length
        double tailPct = getSize();

        // Wow1 controls the foreground vs. gradient color mix.  More Wow1 == more gradient
        double gradientMix = getWow1();

// From a discussion of frame buffer-less, multidimensional KITT patterns
// on the Pixelblaze forum.
// https://forum.electromage.com/t/kitt-without-arrays/1219
        for (TEEdgeModel edge : model.getAllEdges()) {
            for (TEEdgeModel.Point point : edge.points) {

                double x = 0.5 * point.frac;
                double pct1 = x - t1;
                double pct2 = -x - t1;

                // create two traveling waves going opposite directions
                double w1 = Math.max(0f, (tailPct - 1 + triangle(pct1) * square(pct1, 0.5)) / tailPct);
                double w2 = Math.max(0f, (tailPct - 1 + triangle(pct2) * square(pct2, 0.5)) / tailPct);

                // gamma correct both waves before combining to keep the brightness gradient smooth where
                // they overlap
                double bri = clamp((w1 * w1) + (w2 * w2),0,1);

                int baseColor = getGradientColor((float) (gradientMix * (1.0-bri)));
                bri = bri * 255;  // scale for LX output

                // set pixel
                colors[point.index] = TEColor.reAlpha(baseColor,(int) bri);
            }
        }
    }
}