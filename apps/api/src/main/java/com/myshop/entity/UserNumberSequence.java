package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Singleton durable counter for ordinary-member business numbers. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_number_sequences")
public class UserNumberSequence {

    @Id
    private Integer id;

    @Column(name = "start_user_no", nullable = false)
    private Long startUserNo;

    @Column(name = "next_user_no", nullable = false)
    private Long nextUserNo;
}
