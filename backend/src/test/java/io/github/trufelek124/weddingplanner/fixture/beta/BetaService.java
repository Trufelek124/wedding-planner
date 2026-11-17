package io.github.trufelek124.weddingplanner.fixture.beta;

import io.github.trufelek124.weddingplanner.fixture.alpha.AlphaApi;
import io.github.trufelek124.weddingplanner.fixture.alpha.internal.AlphaInternals;

/** Uses alpha's interface (allowed) and alpha's internals (the violation the test expects). */
public class BetaService {

    AlphaApi allowed;

    AlphaInternals forbidden;
}
