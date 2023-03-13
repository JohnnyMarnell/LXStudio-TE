package titanicsend.pattern.yoffa.framework;

import heronarts.lx.LX;
import heronarts.lx.color.LinkedColorParameter;
import heronarts.lx.parameter.LXParameter;
import titanicsend.pattern.TEAudioPattern;
import titanicsend.pattern.TEPerformancePattern;

import java.util.*;
import java.util.stream.Collectors;

public abstract class ConstructedPattern extends TEPerformancePattern {

    private final List<PatternEffect> effects;

    protected ConstructedPattern(LX lx) {
        super(lx, false);
        effects = createEffects();
        for (LXParameter parameter : getPatternParameters()) {
            addParameter(parameter.getLabel(), parameter);
        }
        registerCommonColorControl();
    }

    protected Collection<LXParameter> getPatternParameters() {
        return effects.stream()
                .map(PatternEffect::getParameters)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    @Override
    public void onActive() {
        for (PatternEffect effect : effects) {
            effect.onActive();
        }
    }

    @Override
    public void onParameterChanged(LXParameter parameter) {
        for (PatternEffect effect : effects) {
            effect.onParameterChanged(parameter);
        }
    }

    public void onInactive() {
        for (PatternEffect effect : effects) {
            effect.onPatternInactive();
        }
    }

    @Override
    protected void runTEAudioPattern(double deltaMillis) {
        clearColors();
        for (PatternEffect effect : effects) {
            effect.run(deltaMillis);
        }
    }

    protected abstract List<PatternEffect> createEffects();

}
