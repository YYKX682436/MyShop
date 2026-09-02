package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The lightweight product sort-weight patch deliberately accepts one field. */
public record AdminProductSortWeightRequest(@JsonProperty("sort_weight") Integer sortWeight) {
}
