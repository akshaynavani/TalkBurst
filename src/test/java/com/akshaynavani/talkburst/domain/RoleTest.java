package com.akshaynavani.talkburst.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void dispatcherOutranksEveryoneElse() {
        assertThat(Role.DISPATCHER.outranks(Role.SUPERVISOR)).isTrue();
        assertThat(Role.DISPATCHER.outranks(Role.MEMBER)).isTrue();
        assertThat(Role.MEMBER.outranks(Role.DISPATCHER)).isFalse();
        assertThat(Role.MEMBER.outranks(Role.MEMBER)).isFalse();
    }

    @Test
    void onlyDispatcherMayPreemptAndListenerCannotTalk() {
        assertThat(Role.DISPATCHER.canPreempt()).isTrue();
        assertThat(Role.SUPERVISOR.canPreempt()).isFalse();
        assertThat(Role.LISTENER.canTalk()).isFalse();
        assertThat(Role.MEMBER.canTalk()).isTrue();
    }

    @Test
    void parseIsCaseInsensitiveAndDefaultsToMember() {
        assertThat(Role.parse(" supervisor ")).isEqualTo(Role.SUPERVISOR);
        assertThat(Role.parse(null)).isEqualTo(Role.MEMBER);
        assertThat(Role.parse("")).isEqualTo(Role.MEMBER);
        assertThatThrownBy(() -> Role.parse("admin")).isInstanceOf(IllegalArgumentException.class);
    }
}
