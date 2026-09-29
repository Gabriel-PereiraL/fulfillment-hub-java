package dev.fulfillmenthub.runtime.customer;
import jakarta.persistence.*;import java.util.UUID;
@Entity @Table(name="customer_addresses") public class CustomerAddressRow{@Id public UUID id;@ManyToOne(optional=false)@JoinColumn(name="customer_id")public CustomerRow customer;
 @Column(nullable=false,length=40)public String label;@Column(nullable=false)public boolean isDefault;@Column(nullable=false,length=200)public String street;
 @Column(nullable=false,length=20)public String number;@Column(length=100)public String complement;@Column(nullable=false,length=100)public String district;
 @Column(nullable=false,length=100)public String city;@Column(nullable=false,length=50)public String state;@Column(nullable=false,length=10)public String postalCode;
 @Column(nullable=false,length=2)public String country;public Double latitude;public Double longitude;protected CustomerAddressRow(){} }
