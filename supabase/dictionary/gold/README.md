# Gold-standard evaluation sets

Hand-written lists of answers real players commonly type (one per line, optional `|alternative`)
used to MEASURE dictionary quality — they are deliberately kept separate from the curated
source lists so recall is not "graded by its own answer key". `negatives.tsv` holds
`category<TAB>answer<TAB>expected` cases where the answer must not be accepted automatically
(`invalid` = must be rejected, `not_valid` = rejected or sent to the vote).
