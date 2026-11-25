package com.example.engine.render

object ValleyShaders {

    const val SKY_VERT_SHADER = """
        attribute vec2 aPos;
        uniform vec3 uCamLook;
        uniform vec3 uCamRight;
        uniform vec3 uCamUp;
        uniform float uTanHalfFov;
        uniform float uAspect;
        varying vec3 vRayDir;
        void main() {
            vRayDir = uCamLook + uCamRight * (aPos.x * uAspect * uTanHalfFov) + uCamUp * (aPos.y * uTanHalfFov);
            gl_Position = vec4(aPos, 0.999, 1.0);
        }
    """

    /**
     * Native OpenGL ES port of Three.js Sky (Rayleigh/Mie atmospheric scattering + ACES Filmic)
     * with 2D Simplex Noise clouds on a triangular lattice and camera-basis ray interpolation.
     */
    const val SKY_FRAG_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif

        varying vec3 vRayDir;
        uniform vec3 uSunDir;
        uniform vec3 uZenithColor;
        uniform vec3 uHorizonColor;
        uniform vec3 uSunColor;
        uniform float uTime;
        uniform float uTurbidity;
        uniform float uMieG;
        uniform float uCloudCover;

        float hash21(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float simplexNoise(vec2 v) {
            const vec4 C = vec4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
            vec2 i = floor(v + dot(v, C.yy));
            vec2 x0 = v - i + dot(i, C.xx);
            vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
            vec4 x12 = x0.xyxy + C.xxzz;
            x12.xy -= i1;
            i = mod(i, 289.0);

            float a0 = hash21(i) * 6.2831853;
            float a1 = hash21(i + i1) * 6.2831853;
            float a2 = hash21(i + vec2(1.0, 1.0)) * 6.2831853;
            vec2 g0 = vec2(cos(a0), sin(a0));
            vec2 g1 = vec2(cos(a1), sin(a1));
            vec2 g2 = vec2(cos(a2), sin(a2));

            vec3 m = max(0.5 - vec3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
            m = m * m;
            m = m * m;

            vec3 n = vec3(dot(g0, x0), dot(g1, x12.xy), dot(g2, x12.zw));
            return 0.5 + 0.5 * clamp(dot(m, n) * 42.0, -1.0, 1.0);
        }

        float fbmClouds(vec2 p) {
            float v = 0.0;
            float amp = 0.50;
            mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
            for (int i = 0; i < 5; i++) {
                v += amp * simplexNoise(p);
                p = rot * p * 2.07 + vec2(17.3, 9.2);
                amp *= 0.5;
            }
            return v;
        }

        float hgPhase(float cosTheta, float g) {
            float g2 = g * g;
            float denom = pow(max(0.02, 1.0 - 2.0 * g * cosTheta + g2), 1.5);
            return (1.0 - g2) / (4.0 * 3.14159265 * denom);
        }

        vec3 acesFilmic(vec3 x) {
            return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
        }

        void main() {
            vec3 dir = normalize(vRayDir);
            vec3 sunDir = normalize(uSunDir);

            float elev = clamp(dir.y, 0.0, 1.0);
            float skyT = pow(max(elev, 0.0001), 0.54) * step(0.0001, elev);
            vec3 skyCol = mix(uHorizonColor, uZenithColor, skyT);

            float cosTheta = max(dot(dir, sunDir), 0.0);
            float mie = hgPhase(cosTheta, uMieG) * 0.09 * uTurbidity;
            float omt = 1.0 - elev;
            float omt2 = omt * omt;
            float horizonWarmth = (omt2 * omt2) * (cosTheta * cosTheta) * 0.42;
            skyCol += uSunColor * (mie + horizonWarmth);

            float sunDot = dot(dir, sunDir);
            float sunDisc = smoothstep(0.9992, 0.99985, sunDot);
            float sunCorona = pow(max(sunDot, 0.0001), 48.0) * step(0.0, sunDot) * 0.48;
            skyCol += uSunColor * (sunDisc * 2.6 + sunCorona);

            if (dir.y > 0.015) {
                vec2 cloudUv = (dir.xz / (dir.y + 0.24)) * 2.10 + vec2(uTime * 0.014, uTime * 0.006);
                vec2 warp = vec2(fbmClouds(cloudUv * 0.65), fbmClouds(cloudUv * 0.65 + vec2(5.2, 1.3)));
                float density = fbmClouds(cloudUv + (warp - 0.5) * 0.55);

                float threshold = 0.55 - uCloudCover * 0.20;
                float cloudAlpha = smoothstep(threshold, threshold + 0.22, density) * smoothstep(0.015, 0.18, dir.y);

                vec3 cloudShadow = mix(uHorizonColor * 0.85, vec3(0.66, 0.74, 0.84), 0.45);
                vec3 cloudLit = mix(vec3(0.98, 0.98, 0.97), uSunColor, 0.34);
                vec3 cloudCol = mix(cloudShadow, cloudLit, smoothstep(0.36, 0.76, density) * (0.55 + 0.45 * cosTheta));
                skyCol = mix(skyCol, cloudCol, cloudAlpha * 0.86);
            }

            if (dir.y < 0.0) {
                skyCol = mix(uHorizonColor, uHorizonColor * 0.90, clamp(-dir.y * 1.5, 0.0, 1.0));
            }

            gl_FragColor = vec4(acesFilmic(skyCol * 1.08), 1.0);
        }
    """

    const val SHADOW_VERT_SHADER = """
        uniform mat4 uLightVP;
        attribute vec3 aPos;
        varying float vDepth;
        void main() {
            vec4 clip = uLightVP * vec4(aPos, 1.0);
            vDepth = clip.z / max(clip.w, 0.0001) * 0.5 + 0.5;
            gl_Position = clip;
        }
    """

    /**
     * Packs normalized directional sun depth [0,1] into 16-bit RG channels for universal GLES 2.0 FBO compatibility.
     */
    const val SHADOW_FRAG_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying float vDepth;
        void main() {
            float d = clamp(vDepth, 0.0, 1.0);
            float scaled = d * 255.0;
            float hi = floor(scaled) / 255.0;
            float lo = fract(scaled);
            gl_FragColor = vec4(hi, lo, 0.0, 1.0);
        }
    """

    const val WORLD_VERT_SHADER = """
        uniform mat4 uMVP;
        uniform mat4 uLightVP;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        attribute vec4 aColor;
        attribute vec2 aUV;
        attribute float aTile;

        varying vec3 vWorldPos;
        varying vec3 vNormal;
        varying vec4 vColor;
        varying vec2 vUV;
        varying float vTile;
        varying vec4 vLightClip;

        void main() {
            vWorldPos = aPos;
            vNormal = aNormal;
            vColor = aColor;
            vUV = aUV;
            vTile = aTile;
            vLightClip = uLightVP * vec4(aPos, 1.0);
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """

    const val WORLD_FRAG_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif

        uniform sampler2D uAtlas;
        uniform sampler2D uPbrAtlas;
        uniform sampler2D uShadowMap;
        uniform float uHasShadow;
        uniform float uShadowStrength;
        uniform vec3 uShadowFocus;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uSkyColor;
        uniform vec3 uFogColor;
        uniform vec3 uCamPos;
        uniform float uWaterLevel;
        uniform float uClipActive;
        uniform float uClipY;
        uniform float uFastPass;
        uniform float uPointLightCount;
        uniform vec4 uPointLightPos[3];
        uniform vec3 uPointLightColor[3];

        varying vec3 vWorldPos;
        varying vec3 vNormal;
        varying vec4 vColor;
        varying vec2 vUV;
        varying float vTile;
        varying vec4 vLightClip;

        vec3 acesFilmic(vec3 x) {
            return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
        }

        float decodeDepth(vec2 rg) {
            return rg.x * (255.0 / 256.0) + rg.y * (1.0 / 256.0);
        }

        float computeSunShadow(float ndotl) {
            if (uHasShadow < 0.5 || uShadowStrength < 0.01) return 1.0;
            vec3 proj = vLightClip.xyz / max(vLightClip.w, 0.0001) * 0.5 + 0.5;
            if (proj.x < 0.01 || proj.x > 0.99 || proj.y < 0.01 || proj.y > 0.99 || proj.z > 0.995) {
                return 1.0;
            }
            float bias = max(0.0028 * (1.0 - ndotl), 0.0008);
            float texel = 1.0 / 1024.0;
            float rnd = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
            float s = sin(rnd);
            float c = cos(rnd);
            mat2 rot = mat2(c, -s, s, c);
            vec2 taps[4];
            taps[0] = vec2(-0.65, -0.35);
            taps[1] = vec2(0.35, -0.65);
            taps[2] = vec2(0.65, 0.35);
            taps[3] = vec2(-0.35, 0.65);

            float lit = 0.0;
            for (int i = 0; i < 4; i++) {
                vec2 off = (rot * taps[i]) * (texel * 2.15);
                float sd = decodeDepth(texture2D(uShadowMap, proj.xy + off).rg);
                lit += (proj.z - bias <= sd) ? 1.0 : 0.0;
            }
            float rawShadow = lit * 0.25;
            float edgeDist = max(abs(proj.x - 0.5), abs(proj.y - 0.5));
            float fade = 1.0 - smoothstep(0.38, 0.48, edgeDist);
            return mix(1.0, rawShadow, uShadowStrength * fade);
        }

        void main() {
            if (uClipActive > 0.5 && vWorldPos.y < uClipY) {
                discard;
            }

            // 5x5 Procedural Atlas lookup with 12px mipmap-safe wrapped padding (inner = 0.18125, pad = 0.009375)
            float tileIdx = floor(vTile + 0.5);
            float col = mod(tileIdx, 5.0);
            float row = floor(tileIdx / 5.0);
            vec2 tileOrigin = vec2(col, row) * 0.20;
            vec2 localUv = fract(vUV) * 0.18125 + 0.009375;
            vec2 tileUv = tileOrigin + localUv;
            vec4 tex = texture2D(uAtlas, tileUv);

            float dist = length(uCamPos - vWorldPos);
            vec3 nGeom = normalize(vNormal);
            vec3 l = normalize(uSunDir);

            vec3 albedo = tex.rgb * vColor.rgb;
            vec3 n = nGeom;
            float ao = tex.a;
            float shadow = 1.0;
            vec3 diffuseScale = vec3(1.0);
            vec3 specCol = vec3(0.0);
            vec3 iblCol = vec3(0.0);
            float outAlpha = vColor.a;

            if (uFastPass < 0.5) {
                // Full-quality main pass: PBR atlas + orthonormal tangent basis + GGX + edge bevel + parallax glass
                vec4 pbr = texture2D(uPbrAtlas, tileUv);
                float roughness = clamp(pbr.b, 0.04, 1.0);
                float metallic = clamp(pbr.a, 0.0, 1.0);

                float bumpFade = 1.0 - smoothstep(45.0, 115.0, dist);
                if (bumpFade > 0.02) {
                    vec2 nXY = pbr.rg * 2.0 - 1.0;
                    float nZ = sqrt(max(0.06, 1.0 - clamp(dot(nXY, nXY), 0.0, 0.94)));
                    vec3 refAxis = abs(nGeom.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
                    vec3 tAxis = normalize(cross(refAxis, nGeom));
                    vec3 bAxis = cross(nGeom, tAxis);
                    vec3 nPerturbed = normalize(nGeom * nZ + tAxis * nXY.x + bAxis * nXY.y);
                    n = normalize(mix(nGeom, nPerturbed, bumpFade * 0.92));
                }

                // Multi-frequency world-space weathering & shoreline/puddle realism (eliminates flat tiling)
                float macroA = sin(vWorldPos.x * 0.11 + cos(vWorldPos.z * 0.09) * 1.35) * 0.5
                             + cos(vWorldPos.z * 0.17 - vWorldPos.x * 0.08) * 0.5;
                float macroB = sin(vWorldPos.x * 0.37 + vWorldPos.z * 0.29)
                             * cos(vWorldPos.z * 0.43 - vWorldPos.x * 0.19);
                if (tileIdx < 3.5) {
                    albedo *= 0.94 + macroA * 0.09 + macroB * 0.04;
                    float wet = 1.0 - smoothstep(uWaterLevel - 0.12, uWaterLevel + 0.58, vWorldPos.y);
                    albedo *= mix(1.0, 0.68, wet);
                    roughness = mix(roughness, 0.15, wet * 0.85);
                    if (tileIdx > 1.5 && tileIdx < 2.5) {
                        // Subtle asphalt damp tire tracks / specular puddle breakup
                        float puddle = smoothstep(0.45, 0.85, macroA * 0.6 + macroB * 0.5);
                        roughness = mix(roughness, 0.26, puddle * 0.65);
                    }
                } else if (tileIdx < 12.5) {
                    albedo *= 0.96 + macroA * 0.04 + macroB * 0.045;
                    roughness = clamp(roughness + macroB * 0.07, 0.08, 0.98);
                }

                // Ground-base contact splash & corner AO + subtle procedural UV bevel catch
                float downFace = clamp(nGeom.y * 0.5 + 0.5, 0.0, 1.0);
                float groundContact = mix(0.85, 1.0, smoothstep(2.9, 4.1, vWorldPos.y));
                vec2 uvEdge = abs(fract(vUV) - 0.5);
                float bevelEdge = smoothstep(0.455, 0.495, max(uvEdge.x, uvEdge.y));
                ao = clamp(tex.a * mix(0.82, 1.0, downFace) * groundContact * mix(1.0, 0.90, bevelEdge * 0.45), 0.42, 1.0);

                shadow = computeSunShadow(max(dot(nGeom, l), 0.0));

                vec3 v = normalize(uCamPos - vWorldPos);
                float ndotv = max(dot(n, v), 0.001);
                float oneMinusV = clamp(1.0 - ndotv, 0.0, 1.0);
                float fresnel = oneMinusV * oneMinusV;
                fresnel = fresnel * fresnel * oneMinusV;
                vec3 f0 = mix(vec3(0.04), albedo, metallic);
                vec3 F = f0 + (vec3(1.0) - f0) * fresnel * (1.0 - roughness * 0.55);
                diffuseScale = (vec3(1.0) - F * 0.52) * (1.0 - metallic * 0.78);

                // GGX Microfacet Specular Lobe
                vec3 h = normalize(l + v);
                float ndoth = max(dot(n, h), 0.0);
                float ndotlSpec = max(dot(n, l), 0.001);
                float alphaR = clamp(roughness * roughness, 0.025, 1.0);
                float a2 = alphaR * alphaR;
                float denom = ndoth * ndoth * (a2 - 1.0) + 1.0;
                float ggxD = a2 / (3.14159265 * max(denom * denom, 0.0005));
                float ggxG = 1.0 / (4.0 * max(ndotv, 0.06) * max(ndotlSpec, 0.06) + 0.10);
                float bevelBoost = 1.0 + bevelEdge * (1.0 - roughness) * 0.45;
                specCol = F * clamp(ggxD * ggxG * 0.38 * bevelBoost, 0.0, 4.5);

                // Analytical Sky Hemisphere IBL reflection
                vec3 refl = reflect(-v, n);
                float reflElev = smoothstep(-0.18, 0.14, refl.y);
                vec3 envSky = mix(uFogColor * 0.94, uSkyColor * 1.15, smoothstep(0.0, 0.76, max(refl.y, 0.0)));
                vec3 envGround = vec3(0.18, 0.20, 0.17);
                vec3 envCol = mix(envGround, envSky, reflElev);
                float envGloss = (1.0 - roughness) * (1.0 - roughness);
                iblCol = envCol * F * (envGloss * (0.28 + metallic * 0.58)) * mix(0.45, 1.0, shadow) * ao;

                // Clear Reflective Architectural Plate Glass + Crisp Metallic Mullions
                if (tileIdx > 13.5 && tileIdx < 14.5) {
                    float mullionMask = smoothstep(0.55, 0.78, metallic);
                    float glassFres = clamp(0.06 + 0.88 * fresnel, 0.06, 0.94);
                    diffuseScale = mix(vec3(0.22), diffuseScale, mullionMask);
                    iblCol = mix(envCol * (0.24 + 0.86 * glassFres), iblCol, mullionMask);
                    specCol *= mix(1.75, 1.0, mullionMask);
                    if (vColor.a < 0.98) {
                        float paneAlpha = mix(0.18, 0.56, glassFres);
                        float glintAlpha = clamp(max(specCol.r, specCol.b) * 0.32, 0.0, 0.48);
                        outAlpha = clamp(mix(max(paneAlpha, glintAlpha), 0.88, mullionMask), 0.16, 0.92);
                    }
                }
            } else {
                // Fast planar reflection pass: single albedo tap, no shadow PCF
                shadow = 0.76 + 0.24 * max(dot(nGeom, l), 0.0);
            }

            float ndotl = max(dot(n, l), 0.0);
            float hemi = clamp(n.y * 0.5 + 0.5, 0.0, 1.0);
            vec3 ambient = mix(vec3(0.24, 0.23, 0.21), uSkyColor * 0.46 + vec3(0.23), hemi) * ao;
            vec3 direct = uSunColor * (ndotl * 1.06 * shadow);

            // Dynamic explosion flash & fire site point lights (up to 3 active lights)
            vec3 pointLightAccum = vec3(0.0);
            for (int i = 0; i < 3; i++) {
                if (float(i) < uPointLightCount) {
                    vec3 toL = uPointLightPos[i].xyz - vWorldPos;
                    float r = max(uPointLightPos[i].w, 0.1);
                    float d2 = dot(toL, toL);
                    if (d2 < r * r) {
                        float d = sqrt(d2);
                        vec3 pDir = toL / max(d, 0.001);
                        float atten = 1.0 - (d / r);
                        atten = atten * atten;
                        float pNdotL = max(dot(n, pDir), 0.0);
                        pointLightAccum += uPointLightColor[i] * ((0.18 + 0.82 * pNdotL) * atten);
                    }
                }
            }

            vec3 lit = albedo * diffuseScale * (ambient + direct + pointLightAccum)
                     + uSunColor * (specCol * shadow)
                     + iblCol;
            vec3 mapped = acesFilmic(lit);

            float distFog = smoothstep(190.0, 410.0, dist);
            float edgeX = smoothstep(236.0, 255.5, abs(vWorldPos.x));
            float edgeZ = smoothstep(172.0, 191.5, abs(vWorldPos.z));
            float fog = max(distFog, max(edgeX, edgeZ));
            vec3 finalCol = mix(mapped, uFogColor, fog);
            gl_FragColor = vec4(finalCol, outAlpha);
        }
    """

    const val WATER_VERT_SHADER = """
        uniform mat4 uMVP;
        uniform mat4 uReflVP;
        uniform float uTime;
        attribute vec3 aPos;
        attribute float aDepth;
        varying vec3 vWorldPos;
        varying vec4 vReflClip;
        varying float vDepth;

        void main() {
            vec3 p = aPos;
            float waveScale = clamp(aDepth * 0.8, 0.0, 1.0);
            p.y += (sin(p.x * 0.45 + uTime * 2.0) * 0.03 + cos(p.z * 0.36 - uTime * 1.5) * 0.03) * waveScale;
            vWorldPos = p;
            vDepth = aDepth;
            vReflClip = uReflVP * vec4(p, 1.0);
            gl_Position = uMVP * vec4(p, 1.0);
        }
    """

    const val WATER_FRAG_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif

        uniform sampler2D uWaterNormal;
        uniform sampler2D uReflectionTex;
        uniform float uHasReflection;
        uniform float uReflMix;
        uniform float uTime;
        uniform vec3 uCamPos;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uSkyColor;
        uniform vec3 uFogColor;

        varying vec3 vWorldPos;
        varying vec4 vReflClip;
        varying float vDepth;

        vec3 acesFilmicWater(vec3 x) {
            return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
        }

        vec3 getWaterNoise(vec2 uv) {
            vec2 uv0 = (uv * 0.075) + vec2(uTime * 0.014, uTime * 0.042);
            vec2 uv1 = (uv * 0.135) + vec2(-uTime * 0.018, uTime * 0.028);
            vec3 n0 = texture2D(uWaterNormal, uv0).xyz * 2.0 - 1.0;
            vec3 n1 = texture2D(uWaterNormal, uv1).xyz * 2.0 - 1.0;
            return normalize(vec3(n0.x + n1.x, 2.2, n0.y + n1.y));
        }

        void main() {
            if (vDepth < -0.02) {
                discard;
            }

            vec3 N = getWaterNoise(vWorldPos.xz);
            vec3 V = normalize(uCamPos - vWorldPos);
            vec3 L = normalize(uSunDir);

            float cosTheta = max(dot(N, V), 0.0);
            float f1 = 1.0 - cosTheta;
            float f2 = f1 * f1;
            float rf0 = 0.04;
            float fresnel = clamp(rf0 + (1.0 - rf0) * (f2 * f2 * f1), 0.10, 0.94);

            vec3 reflSample = mix(uSkyColor * 0.95, uFogColor, 0.25);
            if (uHasReflection > 0.5 && uReflMix > 0.01 && vReflClip.w > 0.001) {
                vec2 projUv = (vReflClip.xy / vReflClip.w) * 0.5 + 0.5;
                float edgeFade = smoothstep(0.0, 0.08, projUv.x) * smoothstep(1.0, 0.92, projUv.x)
                               * smoothstep(0.0, 0.08, projUv.y) * smoothstep(1.0, 0.92, projUv.y);
                vec2 distortUv = clamp(projUv + N.xz * 0.028, vec2(0.004), vec2(0.996));
                vec3 planarCol = texture2D(uReflectionTex, distortUv).rgb;
                reflSample = mix(reflSample, planarCol, edgeFade * uReflMix);
            }

            float depthT = clamp(vDepth / 1.85, 0.0, 1.0);
            vec3 shallowRiver = vec3(0.16, 0.48, 0.52);
            vec3 deepRiver = vec3(0.04, 0.20, 0.28);
            vec3 waterBody = mix(shallowRiver, deepRiver, depthT) * (0.72 + 0.28 * max(dot(N, L), 0.0));

            vec3 surfaceCol = mix(waterBody, reflSample, clamp(fresnel * 0.92 + 0.16, 0.18, 0.90));

            vec3 R = reflect(-L, N);
            float vr = dot(V, R);
            float spec = pow(max(vr, 0.0001), 140.0) * step(0.0, vr) * 1.45;
            surfaceCol += uSunColor * spec;

            float shoreMask = smoothstep(0.32, 0.03, vDepth) * smoothstep(-0.01, 0.04, vDepth);
            float waveFoam = clamp(0.45 + N.x * 1.8 + sin(vWorldPos.z * 2.2 + uTime * 2.8) * 0.35, 0.0, 1.0);
            surfaceCol = mix(surfaceCol, vec3(0.92, 0.96, 0.99), shoreMask * waveFoam * 0.42);
            vec3 mapped = acesFilmicWater(surfaceCol);

            float dist = length(uCamPos - vWorldPos);
            float distFog = smoothstep(190.0, 410.0, dist);
            float edgeX = smoothstep(236.0, 255.5, abs(vWorldPos.x));
            float edgeZ = smoothstep(172.0, 191.5, abs(vWorldPos.z));
            float fog = max(distFog, max(edgeX, edgeZ));
            vec3 finalCol = mix(mapped, uFogColor, fog);

            float shoreAlpha = smoothstep(-0.02, 0.20, vDepth);
            float waterAlpha = shoreAlpha * mix(0.60, 0.92, clamp(depthT + fresnel * 0.45, 0.0, 1.0));
            gl_FragColor = vec4(finalCol, waterAlpha);
        }
    """

    const val LINE_VERT_SHADER = """
        uniform mat4 uMVP;
        attribute vec3 aPos;
        attribute vec4 aColor;
        varying vec4 vColor;
        void main() {
            vColor = aColor;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """

    const val LINE_FRAG_SHADER = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """

    const val PARTICLE_VERT_SHADER = """
        uniform mat4 uMVP;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        attribute vec4 aColor;
        attribute vec2 aUV;
        attribute float aTile;
        varying vec4 vColor;
        varying vec2 vUV;
        varying float vMode;
        varying float vWarmth;
        varying vec3 vNormal;
        varying vec3 vWorld;
        void main() {
            vColor = aColor;
            vUV = aUV;
            vMode = aTile;
            vWarmth = aNormal.x;
            vNormal = aNormal;
            vWorld = aPos;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """

    const val PARTICLE_FRAG_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif

        uniform sampler2D uParticleTex;
        uniform vec3 uSunDir;
        uniform vec3 uCamPos;
        varying vec4 vColor;
        varying vec2 vUV;
        varying float vMode;
        varying float vWarmth;
        varying vec3 vNormal;
        varying vec3 vWorld;

        void main() {
            float mode = floor(vMode + 0.5);
            if (mode > 3.5) {
                vec3 N = normalize(vNormal);
                vec3 toCam = uCamPos - vWorld;
                float vLen = length(toCam);
                vec3 V = vLen > 0.001 ? toCam / vLen : vec3(0.0, 1.0, 0.0);
                float ndv = dot(N, V);
                if (ndv < 0.05) discard;
                float fres = pow(clamp(ndv, 0.0, 1.0), 1.15);
                vec2 duv = vec2(
                    fract(vUV.x + N.x * 0.37) * 0.5,
                    0.5 + fract(vUV.y + N.z * 0.37) * 0.5
                );
                float noise = texture2D(uParticleTex, duv).a;
                float puff = fres * (0.45 + 0.55 * noise) * vColor.a;
                if (puff < 0.02) discard;
                float wrap = clamp(dot(N, normalize(uSunDir)) * 0.5 + 0.5, 0.0, 1.0);
                vec3 col = mix(vColor.rgb * 0.48, vColor.rgb * 1.12, wrap);
                col += vec3(0.16, 0.15, 0.13) * pow(1.0 - clamp(ndv, 0.0, 1.0), 2.0);
                gl_FragColor = vec4(col, puff);
                return;
            }

            vec4 tex = texture2D(uParticleTex, vUV);
            float alpha = tex.a * vColor.a;
            if (alpha < 0.015) discard;

            if (mode > 1.5 && mode < 2.5) {
                // Incandescent spark / tracer core
                vec3 col = mix(vColor.rgb, vec3(1.0, 0.98, 0.90), tex.r * 0.85);
                gl_FragColor = vec4(col, alpha);
            } else if (mode > 0.5 && mode < 1.5) {
                // Turbulent fireball / flickering flame tongue
                vec3 fireCol = mix(vColor.rgb * 0.82, vec3(1.0, 0.95, 0.68), tex.r);
                gl_FragColor = vec4(fireCol, alpha);
            } else if (mode > 2.5) {
                // Water splash droplet mist
                vec3 waterCol = mix(vColor.rgb * 0.88, vec3(0.95, 0.99, 1.0), tex.r * 0.50);
                gl_FragColor = vec4(waterCol, alpha);
            } else {
                // Volumetric convective smoke or ground dust with baked lobe shade + backlit sun scatter
                float sunBias = clamp(uSunDir.y * 0.42 + 0.58, 0.45, 1.0);
                vec3 smokeBase = vColor.rgb * mix(0.68, 1.24, tex.r * sunBias);
                vec3 emberGlow = vec3(0.95, 0.42, 0.12) * (vWarmth * (1.0 - tex.r) * 0.55);
                vec3 finalCol = smokeBase + vec3(0.10, 0.10, 0.11) * (tex.g * 0.35 * sunBias) + emberGlow;
                gl_FragColor = vec4(finalCol, alpha);
            }
        }
    """
}
