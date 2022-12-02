package com.flomobility.anx.hermes.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.flomobility.anx.databinding.IpSingleItemBinding
import com.flomobility.anx.hermes.model.IpAddress
import com.flomobility.anx.hermes.other.handleExceptions

class IpAdapter(
    private val context: Context,
    private var ipList: List<IpAddress>,
) :
    RecyclerView.Adapter<IpAdapter.IpViewHolder>() {
    inner class IpViewHolder(val bind: IpSingleItemBinding) : RecyclerView.ViewHolder(bind.root)

    private var onLongClick: ((IpAddress) -> Unit)? = null

    fun doOnLongClick(onLongClick: (IpAddress) -> Unit) {
        this.onLongClick = onLongClick
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IpViewHolder {
        return IpViewHolder(
            IpSingleItemBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: IpViewHolder, position: Int) {
        val bind = holder.bind
        val ipAddress = ipList[position]
//        bind.deviceName.text = device.name
//        Timber.d(device.mac)
        handleExceptions {
            bind.ip.text = "\u2022 $ipAddress"
        }
        bind.root.setOnLongClickListener {
            onLongClick?.invoke(ipAddress)
            return@setOnLongClickListener true
        }
    }

    override fun getItemCount(): Int {
        return ipList.size
    }

    fun updateIpList(ipList: List<IpAddress>) {
        this.ipList = ipList
        this.notifyDataSetChanged()
    }
}
