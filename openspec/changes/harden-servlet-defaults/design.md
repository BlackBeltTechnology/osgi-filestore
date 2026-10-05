## Context

See proposal.md (Why) for the motivation. The constraints that shape the approach:

- **Configuration path.** judo-platform builds every filestore PID in `DispatcherServiceActivator.getAdditionalProperties`, and `TrackerBasedComponentActivator` (lines 166-167) drops `null` values before `configuration.update`. However, the dispatcher's own configuration comes from the config template `judo-platform-config-templates/.../hu.blackbelt.judo.services.dispatcher.osgi.DispatcherServiceActivator.template`, which fills in a value whenever the `JUDO_PLATFORM_*` variable is unset: `filestoreTokenExpiry = ${filestoreTokenExpiry!"0"}`, `filestore.cors.allowOrigin=${corsAllowOrigin!"*"}`, `filestore.cors.allowCredentials=${corsAllowCredentials!"true"}`. These values are never `null`, so for judo-platform the **template** default wins over the annotation default in this repo. A changed annotation default reaches judo-platform only if the template default changes with it.
- **Token round trip in judo.** `ResponseConverter` (lines 116-135) issues a new download token for each binary attribute on every read. `RequestConverter.convertBinaryValue` (line 362) validates any `String` binary value on every save, then keeps only the claims as `FileType`. The database stores `FileType` JSON via `FileTypeFormatter` and no token. So the useful lifetime of a token is "how long a form or tab stays open", not "how long a file is stored".
- **Shared configuration.** `DefaultTokenIssuer` and `DefaultTokenValidator` share `TokenServiceConfig`, and judo-platform feeds both from the same `filestoreTokenExpiry` property. They stay in sync as long as both pick up the same default.
- **Test coverage.** There are no unit tests in `filestore-servlet` or `filestore-security`. Only the Karaf `filestore-itest` covers them, and it configures an explicit CORS origin, so it never exercises the wildcard path.

## Goals / Non-Goals

**Goals:**
- Close the two fail-open defaults with the smallest behavior change that existing deployments can absorb.
- Make every remaining unsafe setting visible in the logs at activation time.

**Non-Goals:**
- Refusing to activate on unsafe configuration. That would turn a log line into an outage on upgrade.
- Changing the judo-platform wiring, or splitting expiry between upload results and download links.
- `Vary: Origin` handling or other caching refinements for CORS.

## Decisions

### D1. Wildcard CORS: send a literal `*` and drop credentials, instead of flipping the `allowCredentials` default

The hole is the combination "echo any origin" plus "allow credentials". Two fixes were considered:

| Option | Effect on explicit-origin configs | Effect on wildcard configs |
|---|---|---|
| Change the `cors_allowCredentials` default to `false` | **Changes them too**: any explicit list that relied on the default loses credentials | Still echoes the origin, now without credentials |
| **Wildcard rule in `CorsProcessor` (chosen)** | Unchanged | Literal `*`, no credentials header |

The wildcard rule fixes the dangerous combination at its source, leaves explicit lists untouched, and matches what browsers accept anyway: they reject `*` together with credentials. The `allowCredentials` default stays `true`, so explicit-list deployments see no difference.

Sending a literal `*` rather than echoing the origin without credentials is the standard form, and it keeps responses identical across origins, which helps shared caches.

### D2. Token expiry default of 1440 minutes, not something short

Because the token covers the time between loading or uploading and saving a form (see Context), any default shorter than a realistic editing session turns into "invalid file token" save errors. 24 h closes the "valid forever" hole for leaked tokens while staying well above any normal session.

An explicit `0` keeps its "never" meaning, so anyone who chose that deliberately is unaffected. Shorter values remain a per-install decision.

Alternative rejected: keeping `0` and only warning. That leaves the fail-open default in place for every judo-platform app.

### D3. Clock-skew tolerance as configuration, default 60 s

jose4j allows 0 s of skew by default. Once tokens carry `exp`, multi-node setups with slightly different clocks would reject tokens near expiry at random. `allowedClockSkew` (seconds) is added to `TokenServiceConfig` and applied by `DefaultTokenValidator` through `JwtConsumerBuilder.setAllowedClockSkewInSeconds`. The issuer ignores it. Making it configurable costs one attribute and avoids a code change if 60 s turns out to be wrong.

### D4. Warnings at activation, once, never blocking

Each check runs in the component's `@Activate` method on the effective configuration and logs one WARN line naming the property. Request handling is unchanged.

The `tokenRequired=false` warning fires on the configuration value alone, not on whether a `TokenValidator` is bound at that moment. The validator reference is optional and dynamic, so its absence at activation proves nothing. The config value is the actual risk signal, and judo-platform sets it to `true`, so platform apps see no noise.

The non-expiring-token warning lives only in `DefaultTokenIssuer`, so a single misconfiguration does not log twice.

### D5. Version

Annotation default values and new metatype attributes are binary compatible, and no exported signature changes. Behavior changes, so this is a **minor** bump of `revision`, not a patch.

## Risks / Trade-offs

- [A form or tab stays open longer than 24 h, then save fails with `ERROR_INVALID_FILE_TOKEN`] → Reloading fixes it. Installs that need longer set `expirationTime` explicitly. Release notes state this.
- [Rolling upgrade: tokens without `exp`, issued by old nodes or held by open tabs, are rejected by upgraded validators] → The window is one session. Release notes advise upgrading outside working hours, or briefly setting `expirationTime=0` during the rollout and then removing it.
- [A cross-origin client relied on credentialed requests against a wildcard config] → None found in the judo-ng frontend sources. The fix is to configure an explicit `cors.allowOrigin` list, and the activation WARN points to it.
- [The Spring runtime (`JudoDefaultSpringConfiguration`) may construct the issuer and validator outside DS and not see annotation defaults] → A verification task checks this. If it bypasses them, the Spring side is unchanged, and that is documented rather than fixed here.

## Migration Plan

1. Release as a minor version. Release notes cover both default changes, how to keep the old behavior (`expirationTime=0`, an explicit origin list) and the 24 h form-session effect.
2. judo-platform needs one template change for the expiry default: `filestoreTokenExpiry = ${filestoreTokenExpiry!"1440"}` in the dispatcher template (branch `feature/JNG-6418_FilestoreTokenExpiryDefault` in judo-platform). Without it, platform apps keep `0` and log the non-expiring-token WARN. The CORS wildcard rule needs no platform change: it lives in `CorsProcessor` and applies to the template's `*` default. `allowedClockSkew` is not passed by the dispatcher, so platform apps always get the 60 s annotation default; making it tunable would need a template line and a dispatcher mapping (out of scope).
3. **Rollback** is configuration only, by setting `expirationTime=0` and/or an explicit `cors.allowOrigin`. Downgrading the bundle is also safe, because nothing is persisted.

## Open Questions

- Whether `JudoDefaultSpringConfiguration` gets its token services through the DS annotation defaults. This affects only the release-note wording, not this repo's code.
