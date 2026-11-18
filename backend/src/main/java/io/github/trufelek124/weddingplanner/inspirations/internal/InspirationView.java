package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationRepository.InspirationRow;
import io.github.trufelek124.weddingplanner.inspirations.internal.PictureRepository.PictureRow;
import java.util.List;

/** An inspiration as it is shown: what is stored about it, and its pictures in their order. */
record InspirationView(InspirationRow inspiration, List<PictureRow> pictures) {}
