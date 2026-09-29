package dev.fulfillmenthub.runtime.customer;
import jakarta.persistence.*;import java.time.Instant;import java.util.*;
@Entity @Table(name="customers") public class CustomerRow{@Id public UUID id;@Version public long version;@Column(nullable=false,unique=true)public UUID userId;
 @Column(nullable=false,length=120)public String name;@Column(nullable=false,unique=true,length=254)public String email;@Column(nullable=false,length=16)public String phone;
 @Column(nullable=false)public boolean active;@Column(nullable=false)public Instant createdAt;@Column(nullable=false)public Instant updatedAt;
 @OneToMany(mappedBy="customer",cascade=CascadeType.ALL,orphanRemoval=true)public List<CustomerAddressRow> addresses=new ArrayList<>();protected CustomerRow(){} }
