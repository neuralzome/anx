//
// Created by Clay-Flo on 03/03/23.
//

#ifndef ANX_IPC_TRANSPORT_H
#define ANX_IPC_TRANSPORT_H

#include <string>
#include <algorithm>
#include <exception>

#include "zmq.hpp"
#include "zmq_addon.hpp"

#include "utils.h"

typedef unsigned char BYTE;   // 8-bit unsigned entity.
typedef BYTE *        PBYTE;  // Pointer to BYTE.

class Publisher {
public:
    Publisher(const std::string& address);
    void SendData(BYTE* data, int length);
    bool close();
private:
    zmq::context_t context_;
    zmq::socket_t socket_;

    std::string address_;

    std::string tag_;
};

#endif //ANX_IPC_TRANSPORT_H
