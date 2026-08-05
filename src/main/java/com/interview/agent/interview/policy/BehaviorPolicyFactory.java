package com.interview.agent.interview.policy;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BehaviorPolicyFactory {
    private final Map<String, BehaviorPolicy> policies;

    public BehaviorPolicyFactory(PressurePolicy pressurePolicy, GentlePolicy gentlePolicy, NeutralPolicy neutralPolicy) {
        this.policies = Map.of(
            "pressure", pressurePolicy,
            "gentle", gentlePolicy,
            "neutral", neutralPolicy
        );
    }

    public BehaviorPolicy getPolicy(String persona) {
        if (persona == null) persona = "neutral";
        return policies.getOrDefault(persona.toLowerCase(), policies.get("neutral"));
    }
}
