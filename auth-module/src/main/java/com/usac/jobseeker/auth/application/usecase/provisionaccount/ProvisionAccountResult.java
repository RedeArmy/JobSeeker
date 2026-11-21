package com.usac.jobseeker.auth.application.usecase.provisionaccount;

import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.domain.model.Actor;

/** La cuenta resultante y si este login la creó (`created`) o ya existía. */
public record ProvisionAccountResult(Actor actor, DeletionStatus deletionStatus, boolean created) {}
