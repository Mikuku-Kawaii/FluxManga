/*
 * Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Modified for FluxManga 2026-10-21: extracted rounded-rectangle distance and
 * circle-map refraction from AndroidLiquidGlass Shaders.kt at
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6. Added a pod / two-lobe smooth union,
 * shared contour normal, bounded sampling and premultiplied material composition.
 * See assets/licenses/liquid-material-NOTICE.txt.
 * Modified 2026-10-25: theme-provided bright/dim rims follow the shared union normal.
 * Optical revision: stronger edge refraction, optional convex zoom, dual-light rim and
 * top/bottom thickness shading. The SDF union and animation geometry are unchanged.
 */
package com.flux.manga.ui.glass

internal const val LiquidMaterialShader = """
uniform shader content;
uniform float2 resolution;
uniform float4 circle;
uniform float4 bud;
uniform float4 pod;
uniform float3 cornerRadii;
uniform float mergeRadius;
uniform float strokeWidth;
uniform float edgeWidth;
uniform float refractionAmount;
uniform float lensMagnification;
uniform float innerWidth;
uniform float highlight;
uniform float sampled;
layout(color) uniform half4 fillColor;
layout(color) uniform half4 tintColor;
layout(color) uniform half4 outlineColor;
layout(color) uniform half4 rimBright;
layout(color) uniform half4 rimDim;

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}
float shapeDistance(float2 p, float4 b, float radius) {
    float2 h = (b.zw - b.xy) * 0.5;
    if (min(h.x, h.y) <= 0.0) return 100000.0;
    return sdRoundedRect(p - (b.xy + b.zw) * 0.5, h, radius);
}
float merge(float a, float b) {
    if (mergeRadius <= 0.0) return min(a, b);
    float h = max(mergeRadius - abs(a - b), 0.0) / mergeRadius;
    return min(a, b) - h * h * mergeRadius * 0.25;
}
float field(float2 p) {
    return merge(merge(shapeDistance(p, circle, cornerRadii.x), shapeDistance(p, pod, cornerRadii.y)), shapeDistance(p, bud, cornerRadii.z));
}
float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}
half4 over(half4 color, half4 base) {
    return half4(color.rgb * color.a, color.a) + base * (1.0 - color.a);
}
half4 main(float2 coord) {
    float sd = field(coord);
    float coverage = 1.0 - smoothstep(-0.5, 0.5, sd);
    if (coverage <= 0.0) return half4(0.0);
    float2 normal = float2(0.0);
    if (highlight > 0.0 || refractionAmount > 0.0) {
        float2 gradient = float2(field(coord + float2(0.5, 0.0)) - field(coord - float2(0.5, 0.0)),
                                 field(coord + float2(0.0, 0.5)) - field(coord - float2(0.0, 0.5)));
        normal = gradient / max(length(gradient), 0.0001);
    }
    float edge = clamp(1.0 + min(sd, 0.0) / max(edgeWidth, 0.001), 0.0, 1.0);
    float2 lensCenter = (pod.xy + pod.zw) * 0.5;
    float interior = 1.0 - edge;
    float2 zoomed = lensCenter + (coord - lensCenter) / (1.0 + lensMagnification * interior);
    float2 lookup = clamp(zoomed - normal * circleMap(edge) * refractionAmount,
                          float2(0.5), resolution - float2(0.5));
    half4 base = sampled > 0.0 ? content.eval(lookup) : half4(0.0);
    base = over(fillColor, base);
    base = over(tintColor, base);
    float border = 1.0 - smoothstep(0.0, strokeWidth, max(-sd, 0.0));
    base = over(half4(outlineColor.rgb, outlineColor.a * border), base);
    float keyLight = pow(max(dot(normal, normalize(float2(-0.6, -0.8))), 0.0), 2.0);
    float bounceLight = pow(max(dot(normal, normalize(float2(0.6, 0.8))), 0.0), 2.0);
    float rimAlpha = 0.15 + 0.60 * keyLight + 0.30 * bounceLight;
    float insideBand = 1.0 - smoothstep(0.0, max(innerWidth, 0.01), max(-sd, 0.0));
    float topBand = 1.0 - smoothstep(0.0, max(innerWidth * 0.5, 0.01), max(-sd, 0.0));
    base = over(half4(rimDim.rgb, rimDim.a * insideBand * max(normal.y, 0.0) * highlight), base);
    base = over(half4(1.0, 1.0, 1.0, 0.40 * topBand * max(-normal.y, 0.0) * highlight), base);
    base = over(half4(rimBright.rgb, rimAlpha * border * highlight), base);
    return base * coverage;
}
"""
