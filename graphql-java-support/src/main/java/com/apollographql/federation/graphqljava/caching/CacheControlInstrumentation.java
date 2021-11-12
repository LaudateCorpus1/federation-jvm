package com.apollographql.federation.graphqljava.caching;

import com.apollographql.federation.graphqljava._Entity;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentation;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters;
import graphql.schema.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class CacheControlInstrumentation extends SimpleInstrumentation {
  private final int defaultMaxAge;
  private final HintCache hintCache = new HintCache();

  private static final String DIRECTIVE_NAME = "cacheControl";
  private static final String EXTENSION_KEY = "cache-control";

  public CacheControlInstrumentation() {
    this(0);
  }

  public CacheControlInstrumentation(int defaultMaxAge) {
    this.defaultMaxAge = defaultMaxAge;
  }

  @Override
  public InstrumentationState createState() {
    return new CacheControlState();
  }

  @Override
  public InstrumentationContext<ExecutionResult> beginField(
      InstrumentationFieldParameters parameters) {
    CacheControlState state = parameters.getInstrumentationState();
    CacheControlPolicy fieldPolicy = new CacheControlPolicy();
    boolean inheritMaxAge = false;

    GraphQLOutputType returnType = parameters.getExecutionStepInfo().getUnwrappedNonNullType();

    // There's no way to set a cacheControl directive on the _entities field or the _Entity union in
    // SDL.
    // Instead, we can determine the possible return types from the representations arguments and
    // select the
    // most restrictive cache policy from that set.

    if (isEntityField(returnType)) {
      Object representations = parameters.getExecutionStepInfo().getArgument(_Entity.argumentName);

      if (representations instanceof List) {
        typesFromEntitiesArgument(
                representations, parameters.getExecutionContext().getGraphQLSchema())
            .stream()
            .map(type -> hintCache.getHint((GraphQLCompositeType) type))
            .filter(Optional::isPresent)
            .forEach(hint -> fieldPolicy.restrict(hint.get()));
      }
    }

    if (returnType instanceof GraphQLList) {
      GraphQLType wrappedType = ((GraphQLList) returnType).getWrappedType();
      if (wrappedType instanceof GraphQLUnionType) {
        if (((GraphQLUnionType) wrappedType).getName().equals(_Entity.typeName)) {
          System.out.printf("found type: %s%n", wrappedType);
        }
      }
    }

    // Cache hint on the return type of this field if it's a composite type

    if (returnType instanceof GraphQLList) {
      returnType = (GraphQLOutputType) ((GraphQLList) returnType).getWrappedType();
    }

    if (returnType instanceof GraphQLCompositeType) {
      Optional<Hint> hint = hintCache.getHint((GraphQLCompositeType) returnType);

      if (hint.isPresent()) {
        fieldPolicy.replace(hint.get());
        inheritMaxAge = hint.get().getInheritMaxAge().orElse(false);
      }
    }

    // Cache hint on the field itself

    Optional<Hint> fieldHint =
        hintCache.getHint(
            parameters.getField(),
            (GraphQLCompositeType) parameters.getExecutionStepInfo().getParent().getType());
    if (fieldHint.isPresent()) {
      Hint hint = fieldHint.get();

      // If inheritMaxAge is true, take note of that to avoid setting the default max age in the
      // next step.
      // This does allow setting the cache scope though.
      //
      // Note: setting both maxAge and inheritMaxAge doesn't make sense.
      if (hint.getInheritMaxAge().orElse(false) && !fieldPolicy.hasMaxAge()) {
        inheritMaxAge = true;
        fieldPolicy.replace(hint.getScope());
      } else {
        fieldPolicy.replace(hint);
      }
    }

    // If this field returns a composite type or is a root field and
    // we haven't seen an explicit maxAge hint, set the maxAge to 0
    // (uncached) or the default if specified in the constructor.
    // (Non-object fields by default are assumed to inherit their
    // cacheability from their parents. But on the other hand, while
    // root non-object fields can get explicit hints from their
    // definition on the Query/Mutation object, if that doesn't exist
    // then there's no parent field that would assign the default
    // maxAge, so we do it here.)
    //
    // You can disable this on a non-root field by writing
    // `@cacheControl(inheritMaxAge: true)` on it. If you do this,
    // then its children will be treated like root paths, since there
    // is no parent maxAge to inherit.

    if (!fieldPolicy.hasMaxAge()
        && ((returnType instanceof GraphQLCompositeType && !inheritMaxAge)
            || parameters.getExecutionStepInfo().getPath().isRootPath())) {
      fieldPolicy.restrict(defaultMaxAge);
    }

    return new SimpleInstrumentationContext<ExecutionResult>() {
      @Override
      public void onCompleted(ExecutionResult result, Throwable t) {
        state.restrictCachePolicy(fieldPolicy);
        super.onCompleted(result, t);
      }
    };
  }

  @Override
  public CompletableFuture<ExecutionResult> instrumentExecutionResult(
      ExecutionResult executionResult, InstrumentationExecutionParameters parameters) {
    final CacheControlState state = parameters.getInstrumentationState();

    if (!state.getOverallPolicyString().isPresent()) {
      return super.instrumentExecutionResult(executionResult, parameters);
    }

    if (!executionResult.getErrors().isEmpty()) {
      return super.instrumentExecutionResult(executionResult, parameters);
    }

    ExecutionResultImpl resultWithCacheControl =
        ExecutionResultImpl.newExecutionResult()
            .from(executionResult)
            .addExtension(EXTENSION_KEY, state.getOverallPolicyString().get())
            .build();

    return CompletableFuture.completedFuture(resultWithCacheControl);
  }

  enum CacheControlScope {
    PUBLIC,
    PRIVATE
  }

  private static class CacheControlState implements InstrumentationState {
    private final CacheControlPolicy overallPolicy = new CacheControlPolicy();

    public Optional<String> getOverallPolicyString() {
      return overallPolicy.getPolicy();
    }

    public void restrictCachePolicy(CacheControlPolicy policy) {
      this.overallPolicy.restrict(policy);
    }
  }

  private static class CacheControlPolicy implements InstrumentationState {
    private Optional<Integer> maxAge = Optional.empty();
    private Optional<CacheControlScope> scope = Optional.of(CacheControlScope.PUBLIC);

    void restrict(CacheControlPolicy policy) {
      if (policy.maxAge.isPresent()
          && (!maxAge.isPresent() || policy.maxAge.get() < maxAge.get())) {
        this.maxAge = policy.maxAge;
      }

      if (policy.scope.isPresent()
          && (!scope.isPresent() || !scope.get().equals(CacheControlScope.PRIVATE))) {
        this.scope = policy.scope;
      }
    }

    void restrict(Hint hint) {
      if (hint.maxAge.isPresent() && (!maxAge.isPresent() || hint.maxAge.get() < maxAge.get())) {
        this.maxAge = hint.maxAge;
      }

      if (hint.scope.isPresent()
          && (!scope.isPresent() || !scope.get().equals(CacheControlScope.PRIVATE))) {
        this.scope = hint.scope;
      }
    }

    void restrict(Integer maxAge) {
      this.maxAge = Optional.of(maxAge);
    }

    void replace(Hint hint) {
      if (hint.maxAge.isPresent()) {
        this.maxAge = hint.maxAge;
      }

      if (hint.scope.isPresent()) {
        this.scope = hint.scope;
      }
    }

    void replace(Optional<CacheControlScope> scope) {
      if (scope.isPresent()) {
        this.scope = scope;
      }
    }

    public Optional<String> getPolicy() {
      Integer maxAgeValue = maxAge.orElse(0);
      if (maxAgeValue.equals(0)) {
        return Optional.empty();
      }
      return Optional.of(
          String.format(
              "max-age=%d, %s",
              maxAgeValue, scope.orElse(CacheControlScope.PUBLIC).toString().toLowerCase()));
    }

    public boolean hasMaxAge() {
      return maxAge.isPresent();
    }

    public boolean hasScope() {
      return scope.isPresent();
    }
  }

  private static class HintCache {
    private final Map<String, Hint> typeHintCache = new HashMap<>();
    private final Map<String, Hint> fieldHintCache = new HashMap<>();

    public Optional<Hint> getHint(
        GraphQLFieldDefinition fieldDefinition, GraphQLCompositeType parentType) {
      String cacheKey = String.format("%s.%s", parentType.getName(), fieldDefinition.getName());

      if (fieldHintCache.containsKey(cacheKey)) {
        return Optional.of(fieldHintCache.get(cacheKey));
      }

      Optional<Hint> fieldHint = Hint.fromDirective(fieldDefinition.getDirective(DIRECTIVE_NAME));
      if (fieldHint.isPresent()) {
        fieldHintCache.put(cacheKey, fieldHint.get());
        return fieldHint;
      }

      return Optional.empty();
    }

    public Optional<Hint> getHint(GraphQLCompositeType type) {
      if (!(type instanceof GraphQLDirectiveContainer)) {
        return Optional.empty();
      }

      GraphQLDirectiveContainer directiveContainer = (GraphQLDirectiveContainer) type;
      String cacheKey = type.getName();

      if (typeHintCache.containsKey(cacheKey)) {
        return Optional.of(typeHintCache.get(cacheKey));
      }

      Optional<Hint> hint = Hint.fromDirective(directiveContainer.getDirective(DIRECTIVE_NAME));
      if (hint.isPresent()) {
        typeHintCache.put(cacheKey, hint.get());
        return hint;
      }

      return Optional.empty();
    }
  }

  private static class Hint {
    private final Optional<Integer> maxAge;
    private final Optional<CacheControlScope> scope;
    private final Optional<Boolean> inheritMaxAge;

    public static Optional<Hint> fromDirective(GraphQLDirective directive) {
      if (directive == null) {
        return Optional.empty();
      }

      Optional<Integer> maxAge =
          Optional.ofNullable(directive.getArgument("maxAge"))
              .map(a -> GraphQLArgument.getArgumentValue(a))
              .filter(v -> v instanceof Integer)
              .map(Integer.class::cast);

      Optional<CacheControlScope> scope =
          Optional.ofNullable(directive.getArgument("scope"))
              .map(a -> GraphQLArgument.getArgumentValue(a))
              .filter(v -> v instanceof String)
              .map(s -> CacheControlScope.valueOf((String) s));

      Optional<Boolean> inheritMaxAge =
          Optional.ofNullable(directive.getArgument("inheritMaxAge"))
              .map(a -> GraphQLArgument.getArgumentValue(a))
              .filter(v -> v instanceof Boolean)
              .map(Boolean.class::cast);

      return Optional.of(new Hint(maxAge, scope, inheritMaxAge));
    }

    public Hint() {
      this.maxAge = Optional.empty();
      this.scope = Optional.empty();
      this.inheritMaxAge = Optional.empty();
    }

    public Hint(
        Optional<Integer> maxAge,
        Optional<CacheControlScope> scope,
        Optional<Boolean> inheritMaxAge) {
      this.maxAge = maxAge;
      this.scope = scope;
      this.inheritMaxAge = inheritMaxAge;
    }

    public boolean isRestricted() {
      return maxAge.isPresent() || scope.isPresent();
    }

    public Optional<Integer> getMaxAge() {
      return maxAge;
    }

    public boolean hasMaxAge() {
      return maxAge.isPresent();
    }

    public Optional<CacheControlScope> getScope() {
      return scope;
    }

    public boolean hasScope() {
      return scope.isPresent();
    }

    public Optional<Boolean> getInheritMaxAge() {
      return inheritMaxAge;
    }

    public boolean hasInheritMaxAge() {
      return inheritMaxAge.isPresent();
    }

    public String toString() {
      return String.format(
          "@cacheControl(maxAge: %s, scope: %s, inheritMaxAge: %s)",
          maxAge.map(Object::toString).orElse("null"),
          scope.map(Enum::toString).orElse("null"),
          inheritMaxAge.map(Object::toString).orElse("null"));
    }
  }

  static boolean isEntityField(GraphQLOutputType returnType) {
    if (returnType instanceof GraphQLList) {
      GraphQLType wrappedType = ((GraphQLList) returnType).getWrappedType();
      if (wrappedType instanceof GraphQLUnionType) {
        return (((GraphQLUnionType) wrappedType).getName().equals(_Entity.typeName));
      }
    }
    return false;
  }

  static List<GraphQLType> typesFromEntitiesArgument(Object representations, GraphQLSchema schema) {
    if (representations instanceof List) {
      return ((List<?>) representations)
          .stream()
              .filter(rep -> rep instanceof Map<?, ?>)
              .map(rep -> ((Map<?, ?>) rep).get("__typename"))
              .map(Object::toString)
              .distinct()
              .map(schema::getType)
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
    }
    return new ArrayList<>();
  }
}
